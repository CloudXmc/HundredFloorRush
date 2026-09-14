package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** 配置 LuckPerms 组的安全开关与组名；不负责执行外部变更。 */
public record LuckPermsConfig(
        boolean enabled,
        boolean createGroups,
        String playerGroup,
        String adminGroup,
        boolean assignPlayerGroupToDefault
) {

    public static LuckPermsConfig parse(ConfigurationSection source) {
        ConfigurationSection section = source == null ? null : source.getConfigurationSection("luckperms");
        if (section == null) {
            return new LuckPermsConfig(true, true, "hfr-player", "hfr-admin", true);
        }
        boolean enabled = bool(section, "enabled", true);
        boolean create = bool(section, "create-groups", true);
        String player = groupName(section, "player-group", "hfr-player");
        String admin = groupName(section, "admin-group", "hfr-admin");
        if (player.equalsIgnoreCase(admin)) {
            throw new ConfigValidationException("luckperms.player-group 与 admin-group 不能相同");
        }
        return new LuckPermsConfig(enabled, create, player, admin,
                bool(section, "assign-player-group-to-default", true));
    }

    private static boolean bool(ConfigurationSection section, String path, boolean fallback) {
        if (!section.contains(path, true)) {
            return fallback;
        }
        if (!section.isBoolean(path)) {
            throw new ConfigValidationException("luckperms." + path + " 必须是 true 或 false");
        }
        return section.getBoolean(path);
    }

    private static String groupName(ConfigurationSection section, String path, String fallback) {
        if (!section.contains(path, true)) {
            return fallback;
        }
        if (!section.isString(path)) {
            throw new ConfigValidationException("luckperms." + path + " 必须是文本");
        }
        String value = section.getString(path, fallback).trim().toLowerCase(Locale.ROOT);
        if (value.length() < 1 || value.length() > 64
                || !value.matches("[a-z0-9_-]+")) {
            throw new ConfigValidationException("luckperms." + path + " 只能包含小写字母、数字、下划线和短横线，长度 1-64");
        }
        return value;
    }
}
