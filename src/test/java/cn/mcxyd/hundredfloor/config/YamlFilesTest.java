package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlFilesTest {

    @Test
    void completesMissingSectionLeavesWithoutSharingDefaultsSection() {
        YamlConfiguration target = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("nested.value", "default");

        assertTrue(YamlFiles.mergeMissing(target, defaults));
        assertEquals("default", target.getString("nested.value"));

        defaults.set("nested.value", "changed");
        assertEquals("default", target.getString("nested.value"));
    }

    @Test
    void rejectsScalarParentInsteadOfOverwritingItDuringCompletion() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("help", "custom scalar");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("help.header", "header");

        assertThrows(ConfigValidationException.class, () -> YamlFiles.mergeMissing(target, defaults));
        assertEquals("custom scalar", target.getString("help"));
    }

    @Test
    void rejectsMalformedTextListEntries() {
        YamlConfiguration target = new YamlConfiguration();
        target.set("lore", List.of("ok", 123));
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("lore", List.of("default"));

        assertThrows(ConfigValidationException.class, () -> YamlFiles.mergeMissing(target, defaults));
    }
}
