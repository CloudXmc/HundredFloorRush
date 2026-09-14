package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {

    @Test
    void pluginDescriptorDeclaresGuiLuckPermsAndFolia() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertTrue(stream != null);
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        assertEquals("1.3.0", yaml.getString("version"));
        assertTrue(yaml.getBoolean("folia-supported"));
        assertTrue(yaml.getConfigurationSection("permissions")
                .isConfigurationSection("hundredfloorrush.gui.admin"));
        assertTrue(yaml.getStringList("softdepend").contains("LuckPerms"));
        assertEquals("/hfr help", yaml.getString("commands.hfr.usage"));
    }
}
