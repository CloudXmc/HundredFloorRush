package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinNpcConfigTest {

    @Test
    void parsesEnabledVillagerNpc() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("join-npc.enabled", true);
        yaml.set("join-npc.entity-type", "VILLAGER");
        yaml.set("join-npc.name", "速下百层");
        yaml.set("join-npc.arena", "main");

        JoinNpcConfig config = JoinNpcConfig.parse(yaml);

        assertTrue(config.enabled());
        assertEquals(EntityType.VILLAGER, config.entityType());
        assertEquals("速下百层", config.name());
        assertEquals("main", config.arena());
    }

    @Test
    void defaultsToTheConfiguredNpc() {
        JoinNpcConfig config = JoinNpcConfig.parse(new YamlConfiguration());

        assertTrue(config.enabled());
        assertEquals(EntityType.VILLAGER, config.entityType());
        assertEquals("速下百层", config.name());
        assertEquals("", config.arena());
    }

    @Test
    void rejectsNonLivingEntityType() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("join-npc.entity-type", "ARROW");

        assertThrows(ConfigValidationException.class, () -> JoinNpcConfig.parse(yaml));
    }

    @Test
    void normalizesConfiguredColorsBeforeMatchingEntityNames() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("join-npc.name", "<green>速下百层</green>");

        JoinNpcConfig config = JoinNpcConfig.parse(yaml);

        assertEquals("速下百层", config.name());
    }

    @Test
    void rejectsWrongScalarTypesAndNullConfiguration() {
        YamlConfiguration booleanYaml = new YamlConfiguration();
        booleanYaml.set("join-npc.enabled", "true");
        assertThrows(ConfigValidationException.class, () -> JoinNpcConfig.parse(booleanYaml));

        YamlConfiguration nameYaml = new YamlConfiguration();
        nameYaml.set("join-npc.name", 123);
        assertThrows(ConfigValidationException.class, () -> JoinNpcConfig.parse(nameYaml));

        assertThrows(ConfigValidationException.class, () -> JoinNpcConfig.parse(null));
    }
}
