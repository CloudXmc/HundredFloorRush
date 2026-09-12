package cn.mcxyd.hundredfloor.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

public final class MessageService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile YamlConfiguration messages;

    public MessageService(YamlConfiguration messages) {
        this.messages = messages;
    }

    public void replace(YamlConfiguration messages) {
        if (messages != null) {
            this.messages = messages;
        }
    }

    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        if (sender != null) {
            try {
                sender.sendMessage(render(key, placeholders));
            } catch (RuntimeException exception) {
                // 消息实现异常不能中断传送、会话清理或物品事务。
                org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                        "发送插件消息失败：" + key, exception);
            }
        }
    }

    public Component render(String key, Map<String, ?> placeholders) {
        return renderTemplate(value("prefix", ""), Map.of())
                .append(renderTemplate(Objects.requireNonNullElse(value(key), ""), placeholders));
    }

    public Component renderRaw(String key, Map<String, ?> placeholders) {
        return renderTemplate(Objects.requireNonNullElse(value(key, ""), ""), placeholders);
    }

    public List<String> rawList(String key) {
        if (key == null || key.isBlank() || messages == null) {
            return List.of();
        }
        try {
            return messages.getStringList(key).stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException ignored) {
            // A malformed optional list must not break an inventory event;
            // callers can safely render an item without lore instead.
            return List.of();
        }
    }

    public Component parseRaw(String text) {
        return safeParse(text);
    }

    private Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        if (text.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacySection().deserialize(text);
        }
        if (text.indexOf('&') >= 0) {
            return LEGACY.deserialize(text);
        }
        return miniMessage.deserialize(text);
    }

    private Component safeParse(String text) {
        try {
            return parse(text);
        } catch (RuntimeException exception) {
            // 配置中的单条消息损坏时降级为纯文本，不能让事件线程因 MiniMessage
            // 解析异常中断；详细配置问题仍可通过控制台的配置文件定位。
            return Component.text(text == null ? "" : text);
        }
    }

    /**
     * Parses the configured template first and inserts placeholder values as
     * plain components afterwards.  Substituting a player/arena supplied
     * value into the template before parsing would allow MiniMessage tags (or
     * legacy colour codes) in that value to change the message formatting.
     * It would also make an ampersand in a value switch a MiniMessage template
     * to the legacy parser.  Keeping the values out of the parser gives every
     * caller the same escaping guarantees on Paper and Folia.
     */
    private Component renderTemplate(String template, Map<String, ?> placeholders) {
        String source = template == null ? "" : template;
        Map<String, String> values = safePlaceholders(placeholders);
        if (values.isEmpty()) {
            return safeParse(source);
        }

        String sanitized = source;
        Map<String, String> tokens = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String placeholder = "{" + key + "}";
            if (!sanitized.contains(placeholder)) {
                continue;
            }
            String token;
            do {
                // Use a token made only of ordinary characters so all three
                // supported parsers preserve it as one literal text run.
                token = "HFR_PLACEHOLDER_" + index++ + "_TOKEN";
            } while (sanitized.contains(token) || containsValue(values, token));
            sanitized = sanitized.replace(placeholder, token);
            tokens.put(token, entry.getValue());
        }

        Component parsed = safeParse(sanitized);
        try {
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                parsed = parsed.replaceText(TextReplacementConfig.builder()
                        .matchLiteral(entry.getKey())
                        // Replace with a plain text builder.  The value is kept
                        // outside every parser, so tags and legacy codes remain
                        // visible text even when the template is user-edited.
                        .replacement(builder -> builder.content(entry.getValue()))
                        .build());
            }
        } catch (RuntimeException ignored) {
            // Adventure implementations supplied by a server/plugin may reject
            // an unusual component tree.  Returning a plain, substituted line
            // is safer than allowing a chat or inventory event to fail.
            return Component.text(substitutePlain(source, values));
        }
        return parsed;
    }

    private static String substitutePlain(String source, Map<String, String> values) {
        String result = source;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static boolean containsValue(Map<String, String> values, String token) {
        for (String value : values.values()) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String value(String key) {
        return value(key, "<red>消息缺失：" + key + "</red>");
    }

    private String value(String key, String fallback) {
        YamlConfiguration current = messages;
        String safeFallback = fallback == null ? "" : fallback;
        if (key == null || key.isBlank() || current == null) {
            return safeFallback;
        }
        try {
            return Objects.requireNonNullElse(current.getString(key, safeFallback), safeFallback);
        } catch (RuntimeException ignored) {
            // ConfigurationSection implementations may reject malformed paths;
            // returning the fallback keeps chat/item rendering fail-safe.
            return safeFallback;
        }
    }

    private static Map<String, String> safePlaceholders(Map<String, ?> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                String value;
                try {
                    // Null is intentionally rendered as an empty value rather
                    // than exposing the implementation string "null".
                    value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                } catch (RuntimeException ignored) {
                    value = "";
                }
                values.put(entry.getKey(), value);
            }
        } catch (RuntimeException ignored) {
            // A concurrently modified/custom map must not propagate into an
            // event thread.  Keep the successfully copied entries only.
        }
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }
}
