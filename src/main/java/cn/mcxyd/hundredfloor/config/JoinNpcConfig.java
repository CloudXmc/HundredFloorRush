package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;

/** 配置点击进入小游戏等待区的实体 NPC。 */
public record JoinNpcConfig(boolean enabled, EntityType entityType, String name, String arena) {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static JoinNpcConfig parse(ConfigurationSection source) {
        if (source == null) {
            throw new ConfigValidationException("主配置不能为空");
        }
        boolean enabled = bool(source, "join-npc.enabled", true);
        String rawType = string(source, "join-npc.entity-type", "VILLAGER");
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ConfigValidationException("join-npc.entity-type 不是有效的实体类型");
        }
        if (!entityType.isAlive()) {
            throw new ConfigValidationException("join-npc.entity-type 必须是可存活实体类型");
        }
        String name = string(source, "join-npc.name", "速下百层");
        if (name == null || name.isBlank()) {
            throw new ConfigValidationException("join-npc.name 不能为空");
        }
        name = plainName(name.trim());
        if (name.isBlank()) {
            throw new ConfigValidationException("join-npc.name 不能为空");
        }
        String arena = string(source, "join-npc.arena", "");
        if (arena == null) {
            arena = "";
        }
        arena = arena.trim();
        return new JoinNpcConfig(enabled, entityType, name, arena);
    }

    private static boolean bool(ConfigurationSection source, String path, boolean defaultValue) {
        if (!source.contains(path, true)) {
            return defaultValue;
        }
        if (!source.isBoolean(path)) {
            throw new ConfigValidationException(path + " 必须是 true 或 false");
        }
        return source.getBoolean(path);
    }

    private static String string(ConfigurationSection source, String path, String defaultValue) {
        if (!source.contains(path, true)) {
            return defaultValue;
        }
        if (!source.isString(path)) {
            throw new ConfigValidationException(path + " 必须是文本");
        }
        return source.getString(path, defaultValue);
    }

    /** 将配置中的 MiniMessage/传统颜色名称规范化为纯文本，和实体 customName 的比较规则一致。 */
    private static String plainName(String value) {
        try {
            if (value.indexOf('§') >= 0) {
                return PLAIN.serialize(SECTION.deserialize(value)).trim();
            }
            if (value.indexOf('&') >= 0) {
                return PLAIN.serialize(AMPERSAND.deserialize(value)).trim();
            }
            return PLAIN.serialize(MINI_MESSAGE.deserialize(value)).trim();
        } catch (RuntimeException ignored) {
            // 配置验证阶段不能因颜色标签写错而导致插件崩溃；退回原始名称并由比较逻辑使用。
            return value.trim();
        }
    }
}
