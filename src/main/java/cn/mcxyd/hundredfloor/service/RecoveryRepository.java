package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.YamlFiles;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RecoveryRepository {

    private final JavaPlugin plugin;
    private final Path file;
    private final Map<UUID, ArenaPoint> returns = new ConcurrentHashMap<>();
    /** 状态锁只保护内存快照；文件写入在异步线程进行，不能阻塞玩家实体线程。 */
    private final Object saveLock = new Object();
    /** 串行化同一文件的写入，防止并发 save 以旧快照覆盖新快照。 */
    private final Object writeLock = new Object();
    private final AtomicLong revision = new AtomicLong();

    public RecoveryRepository(JavaPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        file = plugin.getDataFolder().toPath().resolve("recovery.yml");
    }

    public void load() throws Exception {
        YamlConfiguration yaml = YamlFiles.load(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        Map<UUID, ArenaPoint> loaded = new java.util.HashMap<>();
        if (players == null) {
            synchronized (saveLock) {
                returns.clear();
                revision.incrementAndGet();
            }
            return;
        }
        for (String rawId : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawId);
                ConfigurationSection point = players.getConfigurationSection(rawId);
                if (point != null && validPointSection(point)) {
                    loaded.put(uuid, new ArenaPoint(point.getString("world", ""), point.getDouble("x"),
                            point.getDouble("y"), point.getDouble("z"), (float) point.getDouble("yaw"),
                            (float) point.getDouble("pitch")));
                }
            } catch (IllegalArgumentException | ClassCastException exception) {
                // 单条恢复记录损坏时跳过该条，不能因为玩家数据文件的一行错误而
                // 阻止整个插件启动；原文件保留供管理员检查。
                plugin.getLogger().warning("跳过损坏的恢复记录 " + rawId + "：" + exception.getMessage());
            }
        }
        synchronized (saveLock) {
            returns.clear();
            returns.putAll(loaded);
            revision.incrementAndGet();
        }
    }

    public void remember(UUID uuid, ArenaPoint point) {
        if (uuid == null || point == null) {
            return;
        }
        synchronized (saveLock) {
            returns.put(uuid, point);
            revision.incrementAndGet();
        }
    }

    public Optional<ArenaPoint> find(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(returns.get(uuid));
    }

    public void forget(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (saveLock) {
            if (returns.remove(uuid) != null) {
                revision.incrementAndGet();
            }
        }
    }

    public void save() throws IOException {
        /*
         * 不再把磁盘 IO 放在 saveLock 临界区：remember/forget 通常由玩家实体
         * 线程调用，若它们等待文件系统就会阻塞 Folia Region。writeLock 只负责
         * 串行化多个异步保存；版本检查确保保存期间发生的新记录不会被旧快照
         * 覆盖。调用方可在异步线程安全地重试/记录异常。
         */
        synchronized (writeLock) {
            // 持续掉线/恢复事件不应让一次保存永久占用写锁；最多重试几轮，
            // 最后一轮写入最新快照并交给后续保存请求继续收敛。
            for (int attempt = 0; attempt < 4; attempt++) {
                long capturedRevision;
                YamlConfiguration snapshot;
                synchronized (saveLock) {
                    capturedRevision = revision.get();
                    snapshot = snapshot();
                }
                YamlFiles.saveAtomically(snapshot, file);
                synchronized (saveLock) {
                    if (revision.get() == capturedRevision) {
                        return;
                    }
                }
            }
            YamlConfiguration latest;
            synchronized (saveLock) {
                latest = snapshot();
            }
            YamlFiles.saveAtomically(latest, file);
        }
    }

    private YamlConfiguration snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setComments("players", java.util.List.of(
                "比赛期间意外掉线或停服时的返回坐标。玩家成功返回后会自动删除。",
                "请勿在服务器运行时手工修改此文件。"));
        returns.forEach((uuid, point) -> {
            String root = "players." + uuid;
            yaml.set(root + ".world", point.world());
            yaml.set(root + ".x", point.x());
            yaml.set(root + ".y", point.y());
            yaml.set(root + ".z", point.z());
            yaml.set(root + ".yaw", point.yaw());
            yaml.set(root + ".pitch", point.pitch());
        });
        return yaml;
    }

    private static boolean validPointSection(ConfigurationSection section) {
        if (!section.isString("world") || section.getString("world", "").isBlank()) {
            return false;
        }
        return finiteNumber(section, "x") && finiteNumber(section, "y")
                && finiteNumber(section, "z") && finiteNumber(section, "yaw")
                && finiteNumber(section, "pitch");
    }

    private static boolean finiteNumber(ConfigurationSection section, String path) {
        Object value = section.get(path);
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }
}
