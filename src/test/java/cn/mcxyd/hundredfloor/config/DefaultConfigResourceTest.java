package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConfigResourceTest {

    @Test
    void bundledConfigParsesWithLuckPermsDefaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertTrue(stream != null);
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        GameConfig game = GameConfig.parse(yaml);
        LuckPermsConfig luckPerms = LuckPermsConfig.parse(yaml);
        assertEquals(100, game.floorCount());
        assertEquals("hfr-player", luckPerms.playerGroup());
        assertEquals("hfr-admin", luckPerms.adminGroup());
    }
}
