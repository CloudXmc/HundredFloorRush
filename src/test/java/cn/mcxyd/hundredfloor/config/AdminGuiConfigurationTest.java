package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGuiConfigurationTest {

    @Test
    void bundledLayoutsHaveNineColumnsAndAllUsedIcons() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("gui/admin.yml")) {
            assertTrue(stream != null);
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        ConfigurationSection icons = yaml.getConfigurationSection("icons");
        assertTrue(icons != null);
        for (String path : List.of("layout", "editor-layout", "confirm-layout")) {
            List<String> layout = yaml.getStringList(path);
            assertTrue(layout.size() >= 1 && layout.size() <= 6);
            for (String row : layout) {
                assertEquals(9, row.length(), path + " row width");
                for (char key : row.toCharArray()) {
                    assertTrue(icons.isConfigurationSection(String.valueOf(key)),
                            path + " uses undefined icon " + key);
                }
            }
        }
        assertEquals("AIR", icons.getConfigurationSection("A").getString("material"));
        assertEquals("none", icons.getConfigurationSection("A").getString("action"));
    }
}
