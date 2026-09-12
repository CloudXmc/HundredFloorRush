package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.ConfigurationSection;

/** 代理切服配置。使用 BungeeCord 兼容插件消息协议，可由 Velocity 开启兼容通道后接收。 */
public record ProxyConfig(boolean enabled, String lobbyServer) {

    public static ProxyConfig parse(ConfigurationSection source) {
        if (source == null) {
            throw new ConfigValidationException("主配置不能为空");
        }
        boolean enabled = bool(source, "proxy.enabled", true);
        String lobbyServer = string(source, "proxy.lobby-server", "server");
        if (lobbyServer == null || lobbyServer.isBlank()) {
            throw new ConfigValidationException("proxy.lobby-server 不能为空");
        }
        lobbyServer = lobbyServer.trim();
        if (lobbyServer.length() > 64) {
            throw new ConfigValidationException("proxy.lobby-server 长度不能超过 64 个字符");
        }
        if (!lobbyServer.matches("[A-Za-z0-9_-]+")) {
            throw new ConfigValidationException("proxy.lobby-server 只能包含字母、数字、下划线和短横线");
        }
        return new ProxyConfig(enabled, lobbyServer);
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
