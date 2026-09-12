package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.IdentityMode;
import cn.mcxyd.hundredfloor.config.YamlFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

public final class RecordRepository {

    private final Path file;
    private final Object lock = new Object();
    private YamlConfiguration yaml = new YamlConfiguration();

    public RecordRepository(JavaPlugin plugin) {
        this.file = plugin.getDataFolder().toPath().resolve("records.yml");
    }

    public void load() throws Exception {
        synchronized (lock) {
            yaml = YamlFiles.load(file);
        }
    }

    public OptionalLong best(String arena, String identity) {
        if (arena == null || arena.isBlank() || identity == null || identity.isBlank()) {
            return OptionalLong.empty();
        }
        synchronized (lock) {
            final String path;
            try {
                path = recordPath(arena, identity);
            } catch (IllegalArgumentException exception) {
                return OptionalLong.empty();
            }
            long value = yaml.getLong(path + ".best-millis", -1L);
            // 兼容旧版本使用原始身份作为 YAML 路径的记录；不迁移旧数据，
            // 只在新安全路径尚不存在时读取一次，避免升级后成绩突然消失。
            if (value < 0 && !path.equals(legacyRecordPath(arena, identity))) {
                value = yaml.getLong(legacyRecordPath(arena, identity) + ".best-millis", -1L);
            }
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        }
    }

    public boolean saveIfBetter(String arena, String identity, String lastKnownName, long elapsedMillis,
                             IdentityMode mode, boolean saveName) throws IOException {
        if (arena == null || arena.isBlank() || identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("竞技场和玩家身份不能为空");
        }
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("成绩时间不能为负数");
        }
        Objects.requireNonNull(mode, "mode");
        synchronized (lock) {
            String root = recordPath(arena, identity);
            long old = yaml.getLong(root + ".best-millis", -1L);
            if (old < 0 && !root.equals(legacyRecordPath(arena, identity))) {
                old = yaml.getLong(legacyRecordPath(arena, identity) + ".best-millis", -1L);
            }
            if (old >= 0 && old <= elapsedMillis) {
                return false;
            }
            String bestPath = root + ".best-millis";
            String modePath = root + ".identity-mode";
            String namePath = root + ".last-known-name";
            boolean hadBest = yaml.contains(bestPath);
            boolean hadMode = yaml.contains(modePath);
            boolean hadName = yaml.contains(namePath);
            Object oldBest = yaml.get(bestPath);
            Object oldMode = yaml.get(modePath);
            Object oldName = yaml.get(namePath);
            List<String> oldComments = yaml.getComments("arenas");
            List<String> oldInlineComments = yaml.getInlineComments("arenas");
            try {
                yaml.set(bestPath, elapsedMillis);
                yaml.set(modePath, mode.name());
                if (saveName) {
                    yaml.set(namePath, lastKnownName);
                }
                yaml.setComments("arenas", java.util.List.of(
                        "玩家个人最佳成绩，单位为毫秒。由插件自动维护，请勿在服务器运行时手工修改。",
                        "切换玩家身份模式不会迁移、覆盖或删除这里的旧数据。"));
                YamlFiles.saveAtomically(yaml, file);
                return true;
            } catch (IOException | RuntimeException exception) {
                // 磁盘写入失败时回滚内存 YAML；否则本次请求虽然提示失败，
                // 后续请求却会把尚未落盘的成绩误认为已保存，造成数据丢失。
                restore(yaml, bestPath, hadBest, oldBest);
                restore(yaml, modePath, hadMode, oldMode);
                restore(yaml, namePath, hadName, oldName);
                yaml.setComments("arenas", oldComments);
                yaml.setInlineComments("arenas", oldInlineComments);
                throw exception;
            }
        }
    }

    private static void restore(YamlConfiguration yaml, String path, boolean existed, Object value) {
        if (existed) {
            yaml.set(path, value);
        } else {
            yaml.set(path, null);
        }
    }

    private static String recordPath(String arena, String identity) {
        return "arenas." + arenaKey(arena) + ".records." + identityKey(identity);
    }

    private static String legacyRecordPath(String arena, String identity) {
        return "arenas." + arenaKey(arena) + ".records." + identity.trim();
    }

    private static String arenaKey(String arena) {
        if (arena == null || arena.isBlank()) {
            throw new IllegalArgumentException("竞技场名称不能为空");
        }
        String value = arena.trim().toLowerCase(Locale.ROOT);
        if (value.indexOf('.') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("竞技场名称包含不支持的字符");
        }
        return value;
    }

    /**
     * YAML 配置路径把点号当作层级分隔符。对离线服名称等外部身份进行可逆编码，
     * 避免特殊字符改变路径或让两个身份互相覆盖；只对不含简单安全字符的身份编码，
     * 以保留旧版本大多数记录的路径兼容性。
     */
    private static String identityKey(String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("玩家身份不能为空");
        }
        String value = identity.trim();
        if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("玩家身份无效");
        }
        if (value.matches("[A-Za-z0-9_-]+")) {
            return value;
        }
        return "~" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
