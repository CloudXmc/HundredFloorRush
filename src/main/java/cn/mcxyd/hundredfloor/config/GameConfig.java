package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record GameConfig(
        int countdownSeconds,
        int timeLimitSeconds,
        int minimumPlayers,
        int maximumPlayers,
        int floorCount,
        double gateRadius,
        double finishRadius,
        boolean finishFirework,
        int scoreboardPeriodTicks,
        int resultDisplaySeconds,
        int recoveryCooldownSeconds,
        ProxyConfig proxy,
        JoinNpcConfig joinNpc,
        IdentityMode identityMode,
        boolean offlineNameIgnoreCase,
        boolean saveLastKnownName
) {

    public static GameConfig parse(ConfigurationSection source) {
        if (source == null) {
            throw new ConfigValidationException("主配置不能为空");
        }
        int countdown = ranged(source, "countdown-seconds", 1, 30);
        int timeLimit = ranged(source, "time-limit-seconds", 30, 3_600);
        int minimumPlayers = ranged(source, "minimum-players", 1, 100);
        int maximumPlayers = ranged(source, "maximum-players", 1, 100);
        if (minimumPlayers > maximumPlayers) {
            throw new ConfigValidationException("minimum-players 不能大于 maximum-players");
        }
        int floorCount = ranged(source, "floor-count", 1, 100);
        double gateRadius = rangedDouble(source, "gate-radius", 0.5, 8.0);
        double finishRadius = rangedDouble(source, "finish-radius", 0.5, 16.0);
        boolean finishFirework = bool(source, "finish-firework", true);
        int scoreboardPeriod = ranged(source, "scoreboard-period-ticks", 1, 20);
        int resultDisplay = ranged(source, "result-display-seconds", 1, 30);
        int recoveryCooldown = ranged(source, "recovery-cooldown-seconds", 0, 30, 2);
        ProxyConfig proxy = ProxyConfig.parse(source);
        JoinNpcConfig joinNpc = JoinNpcConfig.parse(source);

        String rawIdentity = string(source, "identity.mode", "OFFLINE_NAME");
        final IdentityMode identityMode;
        try {
            identityMode = IdentityMode.valueOf(rawIdentity.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigValidationException("identity.mode 只能是 OFFLINE_NAME 或 ONLINE_UUID");
        }

        return new GameConfig(countdown, timeLimit, minimumPlayers, maximumPlayers, floorCount,
                gateRadius, finishRadius, finishFirework, scoreboardPeriod,
                resultDisplay, recoveryCooldown, proxy, joinNpc, identityMode,
                bool(source, "identity.offline-name-ignore-case", true),
                bool(source, "identity.save-last-known-name", true));
    }

    private static int ranged(ConfigurationSection source, String path, int min, int max) {
        if (!source.isInt(path)) {
            throw new ConfigValidationException(path + " 必须是整数");
        }
        return ranged(source, path, min, max, source.getInt(path));
    }

    private static int ranged(ConfigurationSection source, String path, int min, int max, int defaultValue) {
        if (source.contains(path, true) && !source.isInt(path)) {
            throw new ConfigValidationException(path + " 必须是整数");
        }
        int value = source.getInt(path, defaultValue);
        if (value < min || value > max) {
            throw new ConfigValidationException(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static double rangedDouble(ConfigurationSection source, String path, double min, double max) {
        if (!source.isDouble(path) && !source.isInt(path)) {
            throw new ConfigValidationException(path + " 必须是数字");
        }
        double value = source.getDouble(path);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ConfigValidationException(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value;
    }

    private static boolean bool(ConfigurationSection source, String path, boolean defaultValue) {
        if (!source.contains(path, true)) {
            return defaultValue;
        }
        if (!source.isBoolean(path)) {
            throw new ConfigValidationException(path + " 必须是 true 或 false");
        }
        return source.getBoolean(path);
    }

    private static String string(ConfigurationSection source, String path, String defaultValue) {
        if (!source.contains(path, true)) {
            return defaultValue;
        }
        if (!source.isString(path)) {
            throw new ConfigValidationException(path + " 必须是文本");
        }
        return source.getString(path, defaultValue);
    }
}
