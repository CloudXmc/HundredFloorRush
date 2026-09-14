package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesConfigurationTest {

    @Test
    void containsAllCommandFailureAndHelpMessages() throws Exception {
        YamlConfiguration messages = new YamlConfiguration();
        try (InputStream stream = MessagesConfigurationTest.class.getClassLoader()
                .getResourceAsStream("messages.yml")) {
            assertTrue(stream != null, "messages.yml must be available on the test classpath");
            messages.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        List<String> required = List.of(
                "operation-failed-generic", "arguments-missing", "arena-name-required", "arena-required", "draft-required",
                "draft-not-found", "draft-incomplete", "selection-required", "invalid-seed", "invalid-floor-count",
                "floor-count-range", "bounds-different-world", "invalid-position-type", "floor-limit",
                "gui-unavailable", "gui-input-name", "gui-input-seed", "gui-input-floors",
                "gui-input-cancelled", "gui-reload-failed", "gui-status-generated", "gui-status-manual",
                "gui-status-running", "arena-delete-running", "help.pos1", "help.pos2", "help.gui");
        for (String key : required) {
            assertTrue(messages.isString(key), "missing message key: " + key);
        }
    }
}
