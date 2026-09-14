package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuckPermsConfigTest {

    @Test
    void defaultsAreSafeAndDistinct() {
        LuckPermsConfig config = LuckPermsConfig.parse(new YamlConfiguration());
        assertEquals("hfr-player", config.playerGroup());
        assertEquals("hfr-admin", config.adminGroup());
        org.junit.jupiter.api.Assertions.assertTrue(config.createGroups());
    }

    @Test
    void invalidGroupNameIsRejected() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("luckperms.player-group", "HFR Admin");
        assertThrows(ConfigValidationException.class, () -> LuckPermsConfig.parse(yaml));
    }
}
