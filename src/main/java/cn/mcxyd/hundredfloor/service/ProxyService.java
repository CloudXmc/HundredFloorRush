package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.ConfigurationManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责通过代理的 BungeeCord 兼容插件消息通道切换子服。
 * 发送必须发生在玩家实体所有者上下文，因此调用方只能从玩家事件或实体调度器进入。
 */
public final class ProxyService implements AutoCloseable {

    public static final String CHANNEL = "BungeeCord";

    private final JavaPlugin plugin;
    private final ConfigurationManager configuration;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ProxyService(JavaPlugin plugin, ConfigurationManager configuration) {
        this.plugin = plugin;
        this.configuration = configuration;
    }

    public void register() {
        closed.set(false);
        refreshRegistration();
    }

    public void refreshRegistration() {
        if (closed.get()) {
            return;
        }
        if (configuration.game().proxy().enabled()) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        } else {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        }
    }

    public boolean enabled() {
        return configuration.game().proxy().enabled();
    }

    /** 返回是否已成功提交插件消息；代理实际切服结果由代理自身决定。 */
    public boolean connectToLobby(Player player) {
        if (closed.get() || player == null || !enabled() || !player.isOnline()) {
            return false;
        }
        try {
            String lobbyServer = configuration.game().proxy().lobbyServer();
            byte[] payload = connectPayload(lobbyServer);
            player.sendPluginMessage(plugin, CHANNEL, payload);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("发送返回大厅请求失败：" + exception.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    static byte[] connectPayload(String server) {
        if (server == null || server.isBlank() || server.length() > 64
                || !server.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("代理子服名称无效");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("Connect");
                output.writeUTF(server);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            // ByteArrayOutputStream 不会抛出 IO 异常；保留明确异常以防未来替换输出实现时静默损坏协议。
            throw new IllegalStateException("无法构造代理切服消息", exception);
        }
    }
}
