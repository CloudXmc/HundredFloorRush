package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.YamlFiles;
import cn.mcxyd.hundredfloor.game.model.ArenaBounds;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ArenaRepository {

    private final JavaPlugin plugin;
    private final Path file;
    private final Map<String, ArenaDefinition> arenas = new ConcurrentHashMap<>();
    private final Map<String, ArenaDraft> drafts = new ConcurrentHashMap<>();
    private final Map<String, GeneratedArena> generatedArenas = new ConcurrentHashMap<>();

    public ArenaRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("arenas.yml");
    }

    public synchronized void load() throws IOException, InvalidConfigurationException {
        // 先在局部集合中完整解析，只有文件成功读取后才替换运行时快照；
        // 这样热重载/启动读取失败时不会把当前可用竞技场清空。
        Map<String, ArenaDefinition> loadedArenas = new LinkedHashMap<>();
        Map<String, GeneratedArena> loadedGenerated = new LinkedHashMap<>();
        if (java.nio.file.Files.exists(file)) {
            YamlConfiguration yaml = YamlFiles.load(file);
            ConfigurationSection section = yaml.getConfigurationSection("arenas");
            if (section != null) {
                for (String name : section.getKeys(false)) {
                    ConfigurationSection arena = section.getConfigurationSection(name);
                    if (arena == null) {
                        continue;
                    }
                    try {
                        requireValidName(name);
                        ArenaDefinition definition = readDefinition(name, arena);
                        if (definition != null) {
                            String normalized = normalize(name);
                            loadedArenas.put(normalized, definition);
                            if (arena.getBoolean("generated", false)) {
                                loadedGenerated.put(normalized, new GeneratedArena(definition,
                                        arena.getLong("seed"), arena.getInt("generated-floor-count",
                                        definition.floorGates().size())));
                            }
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("跳过损坏的竞技场配置 " + name + "：" + exception.getMessage());
                    }
                }
            }
        }
        arenas.clear();
        arenas.putAll(loadedArenas);
        generatedArenas.clear();
        generatedArenas.putAll(loadedGenerated);
        // 草稿只属于本次编辑生命周期，不能在 load 后继续引用旧坐标。
        drafts.clear();
    }

    /**
     * Read-only lookups deliberately do not acquire the mutation monitor.  The
     * backing maps are concurrent, while mutators may hold their monitor during
     * an atomic disk write; blocking a player/Global Region on that file IO can
     * stall every match.  A lookup may observe the short in-memory transition,
     * and callers already validate the returned immutable definition before use.
     */
    public Optional<ArenaDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(arenas.get(normalize(name)));
    }

    public Collection<ArenaDefinition> all() {
        return List.copyOf(arenas.values());
    }

    public Collection<GeneratedArena> generatedArenas() {
        return List.copyOf(generatedArenas.values());
    }

    public synchronized ArenaDraft createDraft(String name) {
        requireValidName(name);
        String normalized = normalize(name);
        ArenaDefinition existing = arenas.get(normalized);
        ArenaDraft draft = new ArenaDraft(existing == null ? name : existing.name());
        if (existing != null) {
            draft.apply(existing);
        }
        drafts.put(normalized, draft);
        return draft;
    }

    public synchronized Optional<ArenaDraft> draft(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(drafts.get(normalize(name)));
    }

    public synchronized void delete(String name) throws IOException {
        requireValidName(name);
        String normalized = normalize(name);
        ArenaDefinition previous = arenas.remove(normalized);
        GeneratedArena previousGenerated = generatedArenas.remove(normalized);
        ArenaDraft previousDraft = drafts.remove(normalized);
        try {
            saveAll();
        } catch (IOException | RuntimeException exception) {
            // 持久化失败时恢复内存快照，避免磁盘与运行时状态分叉。
            if (previous != null) {
                arenas.put(normalized, previous);
            }
            if (previousGenerated != null) {
                generatedArenas.put(normalized, previousGenerated);
            }
            if (previousDraft != null) {
                drafts.put(normalized, previousDraft);
            }
            throw exception;
        }
    }

    public synchronized ArenaDefinition saveDraft(String name) throws IOException {
        requireValidName(name);
        ArenaDraft draft = drafts.get(normalize(name));
        if (draft == null) {
            throw new IllegalArgumentException("没有正在编辑的竞技场");
        }
        ArenaDefinition definition = draft.build();
        return saveDraftSnapshot(name, definition);
    }

    /**
     * 只保存调用方读取时的草稿快照。异步磁盘写入期间若管理员继续编辑，
     * 新草稿不会被旧的 save 请求覆盖或误删。
     */
    public synchronized ArenaDefinition saveDraftSnapshot(String name, ArenaDefinition expected)
            throws IOException {
        requireValidName(name);
        Objects.requireNonNull(expected, "expected");
        String normalized = normalize(name);
        if (!normalized.equals(normalize(expected.name()))) {
            throw new IllegalArgumentException("草稿名称与保存名称不一致");
        }
        ArenaDraft draft = drafts.get(normalized);
        if (draft == null) {
            throw new IllegalStateException("草稿已不存在或已被其他操作处理");
        }
        ArenaDefinition current = draft.build();
        if (!current.equals(expected)) {
            throw new IllegalStateException("草稿已被修改，请重新执行保存");
        }
        saveDefinition(expected);
        drafts.remove(normalized, draft);
        return expected;
    }

    /**
     * Persists an immutable draft snapshot. Callers may run this method on an async IO thread.
     */
    public synchronized void saveDefinition(ArenaDefinition definition) throws IOException {
        Objects.requireNonNull(definition, "definition");
        requireValidName(definition.name());
        String normalized = normalize(definition.name());
        ArenaDefinition previous = arenas.get(normalized);
        GeneratedArena previousGenerated = generatedArenas.get(normalized);
        arenas.put(normalized, definition);
        generatedArenas.remove(normalized);
        try {
            saveAll();
        } catch (IOException | RuntimeException exception) {
            if (previous == null) {
                arenas.remove(normalized);
            } else {
                arenas.put(normalized, previous);
            }
            if (previousGenerated == null) {
                generatedArenas.remove(normalized);
            } else {
                generatedArenas.put(normalized, previousGenerated);
            }
            throw exception;
        }
    }

    public synchronized void saveGenerated(ArenaDefinition definition, long seed) throws IOException {
        Objects.requireNonNull(definition, "definition");
        requireValidName(definition.name());
        String normalized = normalize(definition.name());
        ArenaDefinition previous = arenas.get(normalized);
        GeneratedArena previousGenerated = generatedArenas.get(normalized);
        arenas.put(normalized, definition);
        generatedArenas.put(normalized, new GeneratedArena(definition, seed, definition.floorGates().size()));
        try {
            saveAll();
        } catch (IOException | RuntimeException exception) {
            if (previous == null) {
                arenas.remove(normalized);
            } else {
                arenas.put(normalized, previous);
            }
            if (previousGenerated == null) {
                generatedArenas.remove(normalized);
            } else {
                generatedArenas.put(normalized, previousGenerated);
            }
            throw exception;
        }
    }

    /**
     * 仅当竞技场仍不存在时写入自动生成结果，避免生成线程覆盖管理员刚保存的手工地图。
     * 返回 false 表示名称已被其他操作占用，调用方应将本次生成视为失败。
     */
    public synchronized boolean saveGeneratedIfAbsent(ArenaDefinition definition, long seed) throws IOException {
        Objects.requireNonNull(definition, "definition");
        requireValidName(definition.name());
        String normalized = normalize(definition.name());
        if (arenas.containsKey(normalized)) {
            return false;
        }
        GeneratedArena generated = new GeneratedArena(definition, seed, definition.floorGates().size());
        arenas.put(normalized, definition);
        generatedArenas.put(normalized, generated);
        try {
            saveAll();
            return true;
        } catch (IOException | RuntimeException exception) {
            arenas.remove(normalized, definition);
            generatedArenas.remove(normalized, generated);
            throw exception;
        }
    }

    /**
     * 只有竞技场仍等于调用方读取的快照时才删除，防止异步 delete 请求误删新建/更新的地图。
     */
    public synchronized boolean deleteIfUnchanged(String name, ArenaDefinition expected) throws IOException {
        requireValidName(name);
        Objects.requireNonNull(expected, "expected");
        String normalized = normalize(name);
        ArenaDefinition current = arenas.get(normalized);
        if (current == null || !current.equals(expected)) {
            return false;
        }
        ArenaDefinition previous = arenas.remove(normalized);
        GeneratedArena previousGenerated = generatedArenas.remove(normalized);
        ArenaDraft previousDraft = drafts.remove(normalized);
        try {
            saveAll();
            return true;
        } catch (IOException | RuntimeException exception) {
            if (previous != null) {
                arenas.put(normalized, previous);
            }
            if (previousGenerated != null) {
                generatedArenas.put(normalized, previousGenerated);
            }
            if (previousDraft != null) {
                drafts.put(normalized, previousDraft);
            }
            throw exception;
        }
    }

    /** 丢弃指定编辑草稿，不影响已保存的竞技场配置。 */
    public synchronized void discardDraft(String name) {
        if (name != null && !name.isBlank()) {
            drafts.remove(normalize(name));
        }
    }

    /** 插件关闭或重载时清理所有编辑草稿。 */
    public synchronized void clearDrafts() {
        drafts.clear();
    }

    public synchronized void saveAll() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setComments("arenas", List.of("竞技场坐标数据。请使用 /hfr 指令编辑，不建议手工修改。"));
        for (ArenaDefinition definition : arenas.values()) {
            String root = "arenas." + definition.name();
            GeneratedArena generated = generatedArenas.get(normalize(definition.name()));
            yaml.set(root + ".generated", generated != null);
            if (generated != null) {
                yaml.set(root + ".seed", generated.seed());
                yaml.set(root + ".generated-floor-count", generated.floorCount());
            }
            writePoint(yaml, root + ".waiting", definition.waitingSpawn());
            writePoint(yaml, root + ".start", definition.startSpawn());
            writePoint(yaml, root + ".finish", definition.finishPoint());
            ArenaBounds bounds = definition.bounds();
            yaml.set(root + ".bounds.world", bounds.world());
            yaml.set(root + ".bounds.min-x", bounds.minX());
            yaml.set(root + ".bounds.min-y", bounds.minY());
            yaml.set(root + ".bounds.min-z", bounds.minZ());
            yaml.set(root + ".bounds.max-x", bounds.maxX());
            yaml.set(root + ".bounds.max-y", bounds.maxY());
            yaml.set(root + ".bounds.max-z", bounds.maxZ());
            for (int index = 0; index < definition.floorGates().size(); index++) {
                writePoint(yaml, root + ".floors." + (index + 1), definition.floorGates().get(index));
            }
        }
        YamlFiles.saveAtomically(yaml, file);
    }

    private static ArenaDefinition readDefinition(String name, ConfigurationSection section) {
        ArenaPoint waiting = readPoint(section.getConfigurationSection("waiting"));
        ArenaPoint start = readPoint(section.getConfigurationSection("start"));
        ArenaPoint finish = readPoint(section.getConfigurationSection("finish"));
        ConfigurationSection boundsSection = section.getConfigurationSection("bounds");
        if (waiting == null || start == null || finish == null || boundsSection == null) {
            return null;
        }
        ArenaBounds bounds = new ArenaBounds(boundsSection.getString("world", ""),
                requiredNumber(boundsSection, "min-x"), requiredNumber(boundsSection, "min-y"),
                requiredNumber(boundsSection, "min-z"), requiredNumber(boundsSection, "max-x"),
                requiredNumber(boundsSection, "max-y"), requiredNumber(boundsSection, "max-z"));
        List<ArenaPoint> floors = new ArrayList<>();
        ConfigurationSection floorSection = section.getConfigurationSection("floors");
        if (floorSection != null) {
            floorSection.getKeys(false).stream().filter(ArenaRepository::isInteger)
                    .sorted((left, right) -> Integer.compare(Integer.parseInt(left), Integer.parseInt(right)))
                    .forEach(key -> {
                ArenaPoint point = readPoint(floorSection.getConfigurationSection(key));
                if (point == null) {
                    throw new IllegalArgumentException("第 " + key + " 层坐标无效");
                }
                floors.add(point);
            });
        }
        return new ArenaDefinition(name, waiting, start, finish, bounds, floors);
    }

    private static ArenaPoint readPoint(ConfigurationSection section) {
        if (section == null || !section.isString("world")
                || !finiteNumber(section, "x") || !finiteNumber(section, "y")
                || !finiteNumber(section, "z")
                || (section.contains("yaw", true) && !finiteNumber(section, "yaw"))
                || (section.contains("pitch", true) && !finiteNumber(section, "pitch"))) {
            return null;
        }
        return new ArenaPoint(section.getString("world", ""), number(section, "x"),
                number(section, "y"), number(section, "z"),
                (float) optionalNumber(section, "yaw", 0.0),
                (float) optionalNumber(section, "pitch", 0.0));
    }

    private static double requiredNumber(ConfigurationSection section, String path) {
        if (!finiteNumber(section, path)) {
            throw new IllegalArgumentException("坐标字段 " + path + " 必须是有限数字");
        }
        return number(section, path);
    }

    private static boolean finiteNumber(ConfigurationSection section, String path) {
        Object value = section == null ? null : section.get(path);
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static double number(ConfigurationSection section, String path) {
        return ((Number) section.get(path)).doubleValue();
    }

    private static double optionalNumber(ConfigurationSection section, String path, double fallback) {
        return finiteNumber(section, path) ? number(section, path) : fallback;
    }

    private static void writePoint(YamlConfiguration yaml, String root, ArenaPoint point) {
        yaml.set(root + ".world", point.world());
        yaml.set(root + ".x", point.x());
        yaml.set(root + ".y", point.y());
        yaml.set(root + ".z", point.z());
        yaml.set(root + ".yaw", point.yaw());
        yaml.set(root + ".pitch", point.pitch());
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Bukkit YAML 使用点作为路径分隔符，因此竞技场名称不能包含点或控制字符。 */
    private static void requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("竞技场名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64 || trimmed.indexOf('.') >= 0
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("竞技场名称过长或包含不支持的字符");
        }
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public record GeneratedArena(ArenaDefinition definition, long seed, int floorCount) {
    }

    public static final class ArenaDraft {
        private final String name;
        private ArenaPoint waiting;
        private ArenaPoint start;
        private ArenaPoint finish;
        private ArenaBounds bounds;
        private final List<ArenaPoint> floors = new ArrayList<>();

        private ArenaDraft(String name) {
            this.name = Objects.requireNonNull(name, "name").trim();
        }

        public String name() {
            return name;
        }

        public synchronized void waiting(ArenaPoint point) {
            waiting = point;
        }

        public synchronized void start(ArenaPoint point) {
            start = point;
        }

        public synchronized void finish(ArenaPoint point) {
            finish = point;
        }

        public synchronized void bounds(ArenaBounds bounds) {
            this.bounds = bounds;
        }

        public synchronized int addFloor(ArenaPoint point) {
            floors.add(Objects.requireNonNull(point, "point"));
            return floors.size();
        }

        public synchronized int floorCount() {
            return floors.size();
        }

        public synchronized ArenaDefinition build() {
            if (waiting == null || start == null || finish == null || bounds == null) {
                throw new IllegalArgumentException("等待点、起点、终点和边界必须全部设置");
            }
            // ArenaDefinition 会复制楼层列表；在 synchronized 快照内构建，
            // 避免管理员编辑线程与异步保存线程交叉读写 ArrayList。
            return new ArenaDefinition(name, waiting, start, finish, bounds, List.copyOf(floors));
        }

        private synchronized void apply(ArenaDefinition definition) {
            waiting = definition.waitingSpawn();
            start = definition.startSpawn();
            finish = definition.finishPoint();
            bounds = definition.bounds();
            floors.clear();
            floors.addAll(definition.floorGates());
        }
    }
}
