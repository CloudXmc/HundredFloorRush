package cn.mcxyd.hundredfloor.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates the administrator GUI without allowing arbitrary actions from YAML. */
public final class AdminGuiConfig {

    private final JavaPlugin plugin;
    private final Path file;
    private volatile Snapshot snapshot;

    public AdminGuiConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("gui").resolve("admin.yml");
    }

    public synchronized void initialize() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                plugin.saveResource("gui/admin.yml", false);
            }
            snapshot = load();
        } catch (Exception exception) {
            throw new ConfigValidationException("管理员 GUI 配置加载失败：" + exception.getMessage(), exception);
        }
    }

    public synchronized boolean reload() {
        try {
            Snapshot next = load();
            snapshot = next;
            return true;
        } catch (Exception exception) {
            plugin.getLogger().warning("管理员 GUI 重载失败，继续使用旧配置：" + exception.getMessage());
            return false;
        }
    }

    public Snapshot current() {
        Snapshot value = snapshot;
        if (value == null) {
            throw new IllegalStateException("管理员 GUI 尚未初始化");
        }
        return value;
    }

    private Snapshot load() throws IOException {
        YamlConfiguration yaml;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            yaml = YamlConfiguration.loadConfiguration(reader);
        }
        String title = text(yaml, "title", "速下百层 · 竞技场管理");
        String editorTitle = text(yaml, "editor-title", "编辑竞技场：{arena}");
        String confirmTitle = text(yaml, "confirm-title", "确认删除：{arena}");
        List<String> layout = layout(yaml, "layout");
        List<String> editorLayout = layout(yaml, "editor-layout");
        List<String> confirmLayout = layout(yaml, "confirm-layout");
        ConfigurationSection section = yaml.getConfigurationSection("icons");
        if (section == null) {
            throw new ConfigValidationException("gui/admin.yml 缺少 icons 节点");
        }
        Map<Character, Icon> icons = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (key.length() != 1) {
                plugin.getLogger().warning("管理员 GUI 图标键必须是单个字符，已忽略：" + key);
                continue;
            }
            ConfigurationSection icon = section.getConfigurationSection(key);
            if (icon == null) {
                plugin.getLogger().warning("管理员 GUI 图标定义无效，已忽略：" + key);
                continue;
            }
            Material material = Material.matchMaterial(icon.getString("material", "AIR"));
            if (material == null) {
                throw new ConfigValidationException("gui/admin.yml 图标 " + key + " 的材质无效");
            }
            String action = icon.getString("action", "none");
            if (!ALLOWED_ACTIONS.contains(action)) {
                plugin.getLogger().warning("管理员 GUI 存在未注册 action，已降级为 none：" + action);
                action = "none";
            }
            icons.put(key.charAt(0), new Icon(material, icon.getString("name", ""),
                    List.copyOf(icon.getStringList("lore")), action,
                    Math.max(0, icon.getInt("custom-model-data", 0)),
                    icon.getBoolean("isEnchant", false), icon.getBoolean("hideFlag", true),
                    icon.getBoolean("hideEnchant", true)));
        }
        validateUsed(layout, icons, "layout");
        validateUsed(editorLayout, icons, "editor-layout");
        validateUsed(confirmLayout, icons, "confirm-layout");
        java.util.Set<Character> used = new java.util.HashSet<>();
        for (String row : layout) row.chars().forEach(value -> used.add((char) value));
        for (String row : editorLayout) row.chars().forEach(value -> used.add((char) value));
        for (String row : confirmLayout) row.chars().forEach(value -> used.add((char) value));
        icons.keySet().stream().filter(key -> !used.contains(key))
                .forEach(key -> plugin.getLogger().warning("管理员 GUI 图标未被布局使用：" + key));
        // A is reserved for an empty slot and must never execute an action.
        Icon empty = icons.get('A');
        if (empty == null || !empty.action().equals("none") || empty.material() != Material.AIR) {
            throw new ConfigValidationException("GUI 的 A 图标必须是 AIR 且 action=none");
        }
        return new Snapshot(title, editorTitle, confirmTitle, layout, editorLayout, confirmLayout,
                Collections.unmodifiableMap(icons));
    }

    private static final java.util.Set<String> ALLOWED_ACTIONS = java.util.Set.of(
            "none", "open-arena", "create-map", "reload", "close", "set-waiting", "set-start",
            "set-finish", "set-pos1", "set-pos2", "set-bounds", "add-floor", "save", "start",
            "confirm-delete", "delete", "cancel-delete", "back");

    private static String text(YamlConfiguration yaml, String path, String fallback) {
        Object value = yaml.get(path);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String string) || string.length() > 256) {
            throw new ConfigValidationException(path + " 必须是长度不超过 256 的文本");
        }
        return string;
    }

    private static List<String> layout(YamlConfiguration yaml, String path) {
        List<String> values = yaml.getStringList(path);
        if (values.size() < 1 || values.size() > 6) {
            throw new ConfigValidationException(path + " 必须包含 1-6 行");
        }
        List<String> result = new ArrayList<>();
        for (String row : values) {
            if (row == null || row.length() != 9) {
                throw new ConfigValidationException(path + " 的每行必须正好 9 个字符");
            }
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static void validateUsed(List<String> layout, Map<Character, Icon> icons, String path) {
        for (String row : layout) {
            for (char key : row.toCharArray()) {
                if (!icons.containsKey(key)) {
                    throw new ConfigValidationException(path + " 使用了未定义图标：" + key);
                }
            }
        }
    }

    public record Snapshot(String title, String editorTitle, String confirmTitle,
                           List<String> layout, List<String> editorLayout, List<String> confirmLayout,
                           Map<Character, Icon> icons) {
        public Snapshot {
            layout = List.copyOf(layout);
            editorLayout = List.copyOf(editorLayout);
            confirmLayout = List.copyOf(confirmLayout);
            icons = Map.copyOf(icons);
        }
    }

    public record Icon(Material material, String name, List<String> lore, String action,
                       int customModelData, boolean enchant, boolean hideFlag, boolean hideEnchant) {
        public Icon {
            lore = List.copyOf(lore == null ? List.of() : lore);
        }
    }
}
