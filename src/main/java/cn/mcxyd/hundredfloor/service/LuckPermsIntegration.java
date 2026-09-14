package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.LuckPermsConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional LuckPerms adapter. All LuckPerms storage work remains asynchronous. */
public final class LuckPermsIntegration {

    private static final Set<String> PLAYER_PERMISSIONS = Set.of(
            "hundredfloorrush.command.help", "hundredfloorrush.command.join",
            "hundredfloorrush.command.leave");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "hundredfloorrush.command.admin", "hundredfloorrush.gui.admin");

    private final JavaPlugin plugin;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile LuckPerms luckPerms;
    private volatile LuckPermsConfig config;

    public LuckPermsIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize(LuckPermsConfig config) {
        this.config = config;
        if (config == null || !config.enabled() || !config.createGroups()) {
            return;
        }
        RegisteredServiceProvider<LuckPerms> registration =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (registration == null || registration.getProvider() == null) {
            plugin.getLogger().warning("未检测到 LuckPerms，跳过自动权限组配置；插件其他功能不受影响。");
            return;
        }
        luckPerms = registration.getProvider();
        configureGroups(config);
    }

    public void reload(LuckPermsConfig config) {
        if (closed.get()) {
            return;
        }
        pending.forEach(future -> future.cancel(false));
        pending.clear();
        this.config = config;
        initialize(config);
    }

    private void configureGroups(LuckPermsConfig config) {
        CompletableFuture<Group> player = track(luckPerms.getGroupManager().createAndLoadGroup(config.playerGroup()));
        CompletableFuture<Group> admin = player.thenCompose(ignored ->
                track(luckPerms.getGroupManager().createAndLoadGroup(config.adminGroup())));
        track(admin.thenCompose(group -> {
            for (String permission : ADMIN_PERMISSIONS) {
                group.data().add(PermissionNode.builder(permission).build());
            }
            group.data().add(InheritanceNode.builder(config.playerGroup()).build());
            return luckPerms.getGroupManager().saveGroup(group);
        })).whenComplete((ignored, error) -> {
            if (closed.get()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("LuckPerms 管理员组配置失败：" + error.getMessage());
            } else {
                plugin.getLogger().info("LuckPerms 管理员组已准备：" + config.adminGroup());
            }
        });
        track(player.thenCompose(group -> {
            for (String permission : PLAYER_PERMISSIONS) {
                group.data().add(PermissionNode.builder(permission).build());
            }
            return luckPerms.getGroupManager().saveGroup(group);
        })).whenComplete((ignored, error) -> {
            if (closed.get()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("LuckPerms 玩家组配置失败：" + error.getMessage());
            } else {
                plugin.getLogger().info("LuckPerms 玩家组已准备：" + config.playerGroup());
            }
        });
        if (config.assignPlayerGroupToDefault()) {
            track(luckPerms.getGroupManager().modifyGroup("default", group ->
                    group.data().add(InheritanceNode.builder(config.playerGroup()).build())))
                    .whenComplete((ignored, error) -> {
                        if (closed.get()) {
                            return;
                        }
                        if (error != null) {
                            plugin.getLogger().warning("LuckPerms default 组继承配置失败：" + error.getMessage());
                        }
                    });
        }
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        if (future == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms 返回空 Future"));
        }
        pending.add(future);
        future.whenComplete((ignored, error) -> pending.remove(future));
        return future;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.forEach(future -> future.cancel(false));
        pending.clear();
        luckPerms = null;
    }
}
