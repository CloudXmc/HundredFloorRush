package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameConfigTest {

    @Test
    void parsesValidConfiguration() {
        YamlConfiguration yaml = validYaml();
        yaml.set("identity.mode", "ONLINE_UUID");

        GameConfig config = GameConfig.parse(yaml);

        assertEquals(100, config.floorCount());
        assertEquals(IdentityMode.ONLINE_UUID, config.identityMode());
        assertEquals(5, config.scoreboardPeriodTicks());
        assertEquals("server", config.proxy().lobbyServer());
    }

    @Test
    void rejectsInvalidRangesAndPlayerLimits() {
        YamlConfiguration tooFewPlayers = validYaml();
        tooFewPlayers.set("maximum-players", 0);
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(tooFewPlayers));

        YamlConfiguration reversedLimits = validYaml();
        reversedLimits.set("minimum-players", 10);
        reversedLimits.set("maximum-players", 5);
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(reversedLimits));
    }

    @Test
    void rejectsUnknownIdentityMode() {
        YamlConfiguration yaml = validYaml();
        yaml.set("identity.mode", "PLAYER_IP");

        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(yaml));
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfSilentlyUsingBukkitDefaults() {
        YamlConfiguration booleanYaml = validYaml();
        booleanYaml.set("finish-firework", "yes");
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(booleanYaml));

        YamlConfiguration identityYaml = validYaml();
        identityYaml.set("identity.mode", 1);
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(identityYaml));

        YamlConfiguration recoveryYaml = validYaml();
        recoveryYaml.set("recovery-cooldown-seconds", "2");
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(recoveryYaml));

        YamlConfiguration proxyYaml = validYaml();
        proxyYaml.set("proxy.enabled", "true");
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(proxyYaml));
    }

    @Test
    void rejectsNullConfigurationSection() {
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(null));
    }

    @Test
    void rejectsInvalidLobbyServerName() {
        YamlConfiguration yaml = validYaml();
        yaml.set("proxy.lobby-server", "lobby server");
        assertThrows(ConfigValidationException.class, () -> GameConfig.parse(yaml));
    }

    private static YamlConfiguration validYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("countdown-seconds", 5);
        yaml.set("time-limit-seconds", 300);
        yaml.set("minimum-players", 1);
        yaml.set("maximum-players", 16);
        yaml.set("floor-count", 100);
        yaml.set("gate-radius", 1.6);
        yaml.set("finish-radius", 3.0);
        yaml.set("finish-firework", true);
        yaml.set("scoreboard-period-ticks", 5);
        yaml.set("result-display-seconds", 5);
        yaml.set("proxy.enabled", true);
        yaml.set("proxy.lobby-server", "server");
        yaml.set("identity.mode", "OFFLINE_NAME");
        yaml.set("identity.offline-name-ignore-case", true);
        yaml.set("identity.save-last-known-name", true);
        return yaml;
    }
}
