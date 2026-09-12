package cn.mcxyd.hundredfloor.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void placeholderTextCannotInjectMiniMessageOrLegacyFormatting() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "<gray>[HFR]</gray> ");
        configuration.set("message", "<red>原因：{reason}</red>");

        MessageService service = new MessageService(configuration);
        Component rendered = service.render("message",
                Map.of("reason", "<green>伪造标签</green>&c§l"));

        assertEquals("[HFR] 原因：<green>伪造标签</green>&c§l", PLAIN.serialize(rendered));
        // The injected value is a literal text component rather than a new
        // formatting tag (the configured red style is still present in the
        // surrounding component tree).
        assertTrue(rendered.toString().contains("content=\"<green>伪造标签</green>&c§l\""),
                rendered.toString());
    }

    @Test
    void ampersandInPlaceholderDoesNotChangeMiniMessageParser() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "");
        configuration.set("message", "<yellow>值：{value}</yellow>");

        MessageService service = new MessageService(configuration);
        Component rendered = service.renderRaw("message", Map.of("value", "A&B"));

        assertEquals("值：A&B", PLAIN.serialize(rendered));
        assertTrue(rendered.toString().contains("content=\"A&B\""), rendered.toString());
    }

    @Test
    void legacyTemplateKeepsPlaceholderColourCodesLiteral() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "");
        configuration.set("message", "&c值：{value}");

        MessageService service = new MessageService(configuration);
        Component rendered = service.renderRaw("message", Map.of("value", "&a伪造颜色§l"));

        assertEquals("值：&a伪造颜色§l", PLAIN.serialize(rendered));
        assertTrue(rendered.toString().contains("content=\"&a伪造颜色§l\""), rendered.toString());
    }

    @Test
    void nullAndMissingPlaceholderInputsAreSafe() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "");
        configuration.set("message", "值：{value}");

        MessageService service = new MessageService(configuration);
        assertEquals("值：{value}", PLAIN.serialize(service.renderRaw("message", null)));
        assertEquals("值：", PLAIN.serialize(service.renderRaw("message", Map.of("value", ""))));
        assertNotNull(service.render(null, null));
    }

    @Test
    void malformedTemplateFallsBackWithoutThrowing() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "");
        configuration.set("message", "<gradient:#bad");

        MessageService service = new MessageService(configuration);
        assertEquals("<gradient:#bad", PLAIN.serialize(service.renderRaw("message", Map.of())));
    }

    @Test
    void placeholderTokenTextCannotBeReplacedByAnotherPlaceholder() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("prefix", "");
        configuration.set("message", "{a} {b}");
        MessageService service = new MessageService(configuration);

        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("a", "HFR_PLACEHOLDER_1_TOKEN");
        placeholders.put("b", "safe");

        assertEquals("HFR_PLACEHOLDER_1_TOKEN safe",
                PLAIN.serialize(service.renderRaw("message", placeholders)));
    }
}
