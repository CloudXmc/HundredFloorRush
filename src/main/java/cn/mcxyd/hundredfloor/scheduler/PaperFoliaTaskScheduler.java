package cn.mcxyd.hundredfloor.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Paper/Folia 统一调度入口。
 *
 * <p>业务层不应直接调用 BukkitScheduler。Folia 下所有实时对象操作都先进入其
 * Entity/Region/Global 所有者上下文；需要跨上下文传递时只捕获 UUID 或不可变值。</p>
 */
public final class PaperFoliaTaskScheduler implements TaskScheduler {

    /** 异步传送未来丢失时的最终兜底；避免业务恢复状态永久卡在 pending。 */
    private static final long TELEPORT_TIMEOUT_MILLIS = 15_000L;

    private final Plugin plugin;
    private final boolean folia;
    private final Set<TrackedHandle> activeTasks = ConcurrentHashMap.newKeySet();
    /**
     * Async teleport requests are tracked separately from scheduler tasks.  A
     * teleport future can outlive the entity task that started it (for example
     * while a chunk is loading), so keeping an explicit request set lets
     * shutdown cancel the future and release the callback/Player references.
     */
    private final Set<TeleportRequest> activeTeleports = ConcurrentHashMap.newKeySet();
    /** 同一 UUID 同时只允许一个物理传送，避免旧请求在离场后晚到并覆盖新位置。 */
    private final ConcurrentMap<UUID, TeleportRequest> teleportsByPlayer = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final TaskHandle NOOP_HANDLE = new TaskHandle() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean cancelled() {
            return true;
        }
    };

    public PaperFoliaTaskScheduler(Plugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    @Override
    public void runEntity(Entity entity, Runnable task) {
        if (entity == null || task == null || closed.get()) {
            return;
        }
        if (folia) {
            if (Bukkit.isOwnedByCurrentRegion(entity)) {
                safeRun(task);
                return;
            }
            TrackedHandle handle = track(new TrackedHandle(false));
            try {
                handle.bind(new FoliaHandle(entity.getScheduler().run(plugin, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, handle::retire)));
            } catch (RuntimeException exception) {
                // 调度器拒绝任务时必须向调用方暴露“已取消”状态；否则生成器会把
                // 一个永远不会执行的批次当作成功登记，直到看门狗超时。
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(task);
            return;
        }
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            handle.bind(new BukkitHandle(Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                                        if (!closed.get() && !handle.cancelled()) {
                        safeRun(task);
                    }
                } finally {
                    handle.complete();
                }
            })));
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
    }

    @Override
    public void runPlayer(UUID playerId, Consumer<Player> task) {
        if (playerId == null || task == null || closed.get()) {
            return;
        }
        TrackedHandle handle = track(new TrackedHandle(false));
        if (folia) {
            // 先进入全局线程，再切到玩家实体线程。两段均至少延迟 1 tick，避免从
            // 任意调用线程读取或修改 Player，并且可以在关闭时取消未执行的回调。
            try {
                handle.bind(new FoliaHandle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> {
                    if (closed.get() || handle.cancelled()) {
                        handle.complete();
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) {
                        handle.complete();
                        return;
                    }
                    try {
                        handle.bind(new FoliaHandle(player.getScheduler().runDelayed(plugin, ignoredEntity -> {
                            try {
                                // 玩家可能在全局排队阶段断线并由同 UUID 新实体替换；
                                // 只向仍是原实例的实体投递，避免旧任务写入新连接状态。
                                if (!closed.get() && !handle.cancelled() && player.isOnline()
                                        && Bukkit.getPlayer(playerId) == player) {
                                    safeRun(() -> task.accept(player));
                                }
                            } finally {
                                handle.complete();
                            }
                        }, handle::retire, 1)));
                    } catch (RuntimeException exception) {
                        handle.cancel();
                        handle.complete();
                        logSchedulingFailure(exception);
                    }
                }, 1)));
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
            return;
        }
        try {
            handle.bind(new BukkitHandle(Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (closed.get() || handle.cancelled()) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        safeRun(() -> task.accept(player));
                    }
                } finally {
                    handle.complete();
                }
            })));
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
    }

    @Override
    public TaskHandle runEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        if (entity == null || task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(entity.getScheduler().runDelayed(plugin, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, handle::retire, delay)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            }
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
        return handle;
    }

    @Override
    public TaskHandle runEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (entity == null || task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        long period = atLeastOne(periodTicks);
        TrackedHandle handle = track(new TrackedHandle(true));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(entity.getScheduler().runAtFixedRate(plugin,
                        ignored -> {
                                        if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, handle::retire, delay, period)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskTimer(plugin,
                        () -> {
                                        if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, delay, period)));
            }
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
        return handle;
    }

    @Override
    public void runRegion(Location location, Runnable task) {
        if (location == null || location.getWorld() == null || task == null || closed.get()) {
            return;
        }
        if (folia) {
            if (Bukkit.isOwnedByCurrentRegion(location)) {
                safeRun(task);
                return;
            }
            TrackedHandle handle = track(new TrackedHandle(false));
            try {
                handle.bind(new FoliaHandle(Bukkit.getRegionScheduler().run(plugin, location, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                })));
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(task);
            return;
        }
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            handle.bind(new BukkitHandle(Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                                        if (!closed.get() && !handle.cancelled()) {
                        safeRun(task);
                    }
                } finally {
                    handle.complete();
                }
            })));
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
    }

    @Override
    public TaskHandle runRegionDelayed(Location location, Runnable task, long delayTicks) {
        if (location == null || location.getWorld() == null || task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getRegionScheduler().runDelayed(plugin, location, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            }
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
        return handle;
    }

    @Override
    public TaskHandle runRegionDelayed(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        if (worldName == null || worldName.isBlank() || task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                // 世界对象只在 Global 上下文解析，再把不可变引用交给目标 Region 调度；
                // 不从异步线程直接读取 World/Chunk 状态。
                handle.bind(new FoliaHandle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> {
                    if (closed.get() || handle.cancelled()) {
                        handle.complete();
                        return;
                    }
                    org.bukkit.World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        handle.complete();
                        return;
                    }
                    try {
                        handle.bind(new FoliaHandle(Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
                                ignoredRegion -> {
                                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                                            safeRun(task);
                                        }
                                    } finally {
                                        handle.complete();
                                    }
                                }, delay)));
                    } catch (RuntimeException exception) {
                        handle.cancel();
                        handle.complete();
                        logSchedulingFailure(exception);
                    }
                }, 1)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            }
        } catch (RuntimeException exception) {
            handle.cancel();
            handle.complete();
            logSchedulingFailure(exception);
        }
        return handle;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (task == null || closed.get()) {
            return;
        }
        if (folia) {
            if (Bukkit.isGlobalTickThread()) {
                safeRun(task);
                return;
            }
            TrackedHandle handle = track(new TrackedHandle(false));
            try {
                handle.bind(new FoliaHandle(Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                })));
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(task);
            return;
        }
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            handle.bind(new BukkitHandle(Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                                        if (!closed.get() && !handle.cancelled()) {
                        safeRun(task);
                    }
                } finally {
                    handle.complete();
                }
            })));
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
    }

    @Override
    public TaskHandle runGlobalDelayed(Runnable task, long delayTicks) {
        if (task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay)));
            }
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
        return handle;
    }

    @Override
    public TaskHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        if (task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = atLeastOne(delayTicks);
        long period = atLeastOne(periodTicks);
        TrackedHandle handle = track(new TrackedHandle(true));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                        ignored -> {
                            if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, delay, period)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskTimer(plugin,
                        () -> {
                                        if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, delay, period)));
            }
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
        return handle;
    }

    @Override
    public void runAsync(Runnable task) {
        if (task == null || closed.get()) {
            return;
        }
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                    try {
                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                })));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                    if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                })));
            }
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
    }

    @Override
    public TaskHandle runAsyncDelayed(Runnable task, long delayMillis) {
        if (task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = Math.max(1L, delayMillis);
        TrackedHandle handle = track(new TrackedHandle(false));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, delay, TimeUnit.MILLISECONDS)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    try {
                                        if (!closed.get() && !handle.cancelled()) {
                            safeRun(task);
                        }
                    } finally {
                        handle.complete();
                    }
                }, millisToTicks(delay))));
            }
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
        return handle;
    }

    @Override
    public TaskHandle runAsyncTimer(Runnable task, long delayMillis, long periodMillis) {
        if (task == null || closed.get()) {
            return NOOP_HANDLE;
        }
        long delay = Math.max(1L, delayMillis);
        long period = Math.max(1L, periodMillis);
        TrackedHandle handle = track(new TrackedHandle(true));
        try {
            if (folia) {
                handle.bind(new FoliaHandle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                        ignored -> {
                                        if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, delay, period, TimeUnit.MILLISECONDS)));
            } else {
                handle.bind(new BukkitHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                        () -> {
                                        if (!closed.get() && !handle.cancelled()) {
                                safeRun(task);
                            }
                        }, millisToTicks(delay), millisToTicks(period))));
            }
            } catch (RuntimeException exception) {
                handle.cancel();
                handle.complete();
                logSchedulingFailure(exception);
            }
        return handle;
    }

    @Override
    public void teleport(UUID playerId, String worldName, double x, double y, double z,
                          float yaw, float pitch, BiConsumer<Player, Boolean> completion) {
        teleportInternal(null, playerId, worldName, x, y, z, yaw, pitch, completion);
    }

    @Override
    public void teleport(Player expectedPlayer, String worldName, double x, double y, double z,
                          float yaw, float pitch, BiConsumer<Player, Boolean> completion) {
        if (expectedPlayer == null) {
            if (completion != null) {
                safeComplete(completion, null, false);
            }
            return;
        }
        teleportInternal(expectedPlayer, expectedPlayer.getUniqueId(), worldName, x, y, z, yaw, pitch,
                completion);
    }

    private void teleportInternal(Player expectedPlayer, UUID playerId, String worldName,
                                  double x, double y, double z, float yaw, float pitch,
                                  BiConsumer<Player, Boolean> completion) {
        if (completion == null) {
            return;
        }
        if (playerId == null || worldName == null || worldName.isBlank() || closed.get()) {
            // No live Player is available here.  Callers must treat a null
            // player as a UUID-only failure and must not touch Bukkit objects.
            safeComplete(completion, null, false);
            return;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            plugin.getLogger().warning("异步传送目标坐标无效，已取消传送请求");
            safeComplete(completion, null, false);
            return;
        }

        TeleportRequest request = new TeleportRequest(expectedPlayer, playerId, worldName, x, y, z,
                yaw, pitch, completion);
        if (!activeTeleports.add(request)) {
            // Set implementations should not reject a fresh object, but keep
            // this branch defensive in case a custom collection is introduced.
            safeComplete(completion, null, false);
            return;
        }
        TeleportRequest previous = teleportsByPlayer.put(playerId, request);
        if (previous != null && previous != request) {
            // 业务 token 失效只能阻止回调，不能撤销已提交的物理传送；
            // 这里主动终止同 UUID 的旧请求，保证离场/恢复传送不会互相覆盖。
            previous.abort();
        }
        request.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Cancel teleport futures before scheduler cancellation.  The latter
        // only covers tasks submitted through Bukkit/Folia schedulers and does
        // not necessarily interrupt a Player#teleportAsync future.
        activeTeleports.forEach(TeleportRequest::abort);
        activeTeleports.clear();
        teleportsByPlayer.clear();
        activeTasks.forEach(TrackedHandle::cancel);
        activeTasks.clear();
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    /**
     * Registers a handle while closing can happen concurrently.
     *
     * <p>A plain {@code if (!closed) add(handle)} has a small but important race:
     * shutdown may clear the set between the check and the add, after which a
     * repeating task can be submitted after {@code cancelTasks} has already run.
     * The second check closes that window; {@link TrackedHandle#bind(TaskHandle)}
     * closes the remaining submit-vs-close window.</p>
     */
    private TrackedHandle track(TrackedHandle handle) {
        if (closed.get()) {
            handle.cancel();
            return handle;
        }
        activeTasks.add(handle);
        if (closed.get() && activeTasks.remove(handle)) {
            handle.cancel();
        }
        return handle;
    }

    private void safeComplete(BiConsumer<Player, Boolean> completion, Player player, boolean success) {
        if (closed.get()) {
            return;
        }
        try {
            completion.accept(player, success);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "传送回调执行失败", exception);
        }
    }

    private void safeRun(Runnable task) {
        // Direct current-context calls can race with plugin shutdown on Folia.
        // Re-check here as the final common gate so a task cannot start after
        // close() has marked this scheduler closed.
        if (closed.get()) {
            return;
        }
        try {
            task.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "插件任务执行失败", exception);
        }
    }

    private void logSchedulingFailure(RuntimeException exception) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, "插件任务调度失败", exception);
    }

    /**
     * One guarded async teleport.  A completion with a non-null Player is
     * always delivered from that Player's entity owner context.  A null Player
     * means the request failed/expired and the callback must only perform
     * UUID-safe bookkeeping.
     */
    private final class TeleportRequest {
        private final Player requestedPlayer;
        private final UUID playerId;
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final AtomicReference<BiConsumer<Player, Boolean>> callback;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicReference<TaskHandle> timeout = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<Boolean>> future = new AtomicReference<>();
        private final AtomicReference<Player> expectedPlayer;
        /** 世界尚未加载时的短重试句柄；最终仍由 timeout 统一兜底。 */
        private final AtomicReference<TaskHandle> worldRetry = new AtomicReference<>();

        private TeleportRequest(Player requestedPlayer, UUID playerId, String worldName, double x, double y, double z,
                                float yaw, float pitch, BiConsumer<Player, Boolean> callback) {
            this.requestedPlayer = requestedPlayer;
            this.playerId = playerId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.callback = new AtomicReference<>(callback);
            this.expectedPlayer = new AtomicReference<>(requestedPlayer);
        }

        private void start() {
            if (closed.get()) {
                abort();
                return;
            }
            TaskHandle timeoutHandle;
            try {
                timeoutHandle = runAsyncDelayed(this::expire, TELEPORT_TIMEOUT_MILLIS);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "异步传送超时任务无法调度", exception);
                expire();
                return;
            }
            timeout.set(timeoutHandle);
            if (timeoutHandle == null || timeoutHandle.cancelled()) {
                expire();
                return;
            }
            // runPlayer deliberately has no live-object callback when the
            // player is offline.  The timeout above is the bounded fallback.
            if (requestedPlayer != null) {
                runEntity(requestedPlayer, () -> begin(requestedPlayer));
            } else {
                runPlayer(playerId, this::begin);
            }
        }

        private void begin(Player player) {
            if (player == null || delivered.get() || closed.get()) {
                return;
            }
            Player requested = requestedPlayer;
            // 实体任务排队期间玩家可能已经退出并由同 UUID 的新实体替换。
            // 在提交 teleportAsync 前再次绑定实例和在线状态，防止旧 Player
            // 对象被传送，或让旧请求覆盖新连接的位置。
            Player current = Bukkit.getPlayer(playerId);
            if (current != player || !player.isOnline()
                    || (requested != null && (player != requested || requested != current))) {
                deliver(null, false);
                return;
            }
            expectedPlayer.set(player);
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) {
                retryUntilWorldAvailable(player);
                return;
            }
            Location destination = new Location(world, x, y, z, yaw, pitch);
            try {
                CompletableFuture<Boolean> pending = player.teleportAsync(destination);
                future.set(pending);
                if (delivered.get() || closed.get()) {
                    pending.cancel(false);
                    return;
                }
                pending.whenComplete((success, error) -> {
                    Player expected = expectedPlayer.get();
                    // Re-enter the current entity owner context.  If the UUID
                    // now resolves to a different Player instance, never pass
                    // that new entity to the old request's callback.
                    Consumer<Runnable> ownerDispatch = action -> {
                        if (expected != null) {
                            runEntity(expected, action);
                        } else {
                            runPlayer(playerId, ignored -> action.run());
                        }
                    };
                    ownerDispatch.accept(() -> {
                        Player target = Bukkit.getPlayer(playerId);
                        if (target != expected) {
                            deliver(null, false);
                        } else {
                            deliver(target, error == null && Boolean.TRUE.equals(success));
                        }
                    });
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("异步传送请求失败：" + exception.getMessage());
                deliver(player, false);
            }
        }

        /**
         * Stored arena worlds may be created by a queued GlobalRegion task while
         * a player join task is already running.  Do not fail that join
         * immediately; retry on the same entity owner context and let the
         * bounded async timeout handle a genuinely missing world.
         */
        private void retryUntilWorldAvailable(Player player) {
            if (player == null || delivered.get() || closed.get()) {
                return;
            }
            AtomicReference<TaskHandle> scheduled = new AtomicReference<>();
            TaskHandle next;
            try {
                next = runEntityDelayed(player, () -> {
                    TaskHandle current = scheduled.get();
                    if (current != null) {
                        worldRetry.compareAndSet(current, null);
                    }
                    begin(player);
                }, 1L);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "世界尚未加载时的传送重试任务无法调度", exception);
                deliver(null, false);
                return;
            }
            scheduled.set(next);
            if (next == null || next.cancelled()) {
                deliver(null, false);
                return;
            }
            TaskHandle previous = worldRetry.getAndSet(next);
            if (previous != null && previous != next) {
                previous.cancel();
            }
            // A timeout/close may win between scheduling and publication.
            if (delivered.get() || closed.get()) {
                if (worldRetry.compareAndSet(next, null)) {
                    next.cancel();
                }
            }
        }

        private void expire() {
            if (delivered.get()) {
                return;
            }
            // Canceling is best effort; Paper may already have committed the
            // move.  The callback is still released exactly once, and any
            // stale completion is ignored by delivered.
            CompletableFuture<Boolean> pending = future.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            deliver(null, false);
        }

        private void deliver(Player player, boolean success) {
            if (!delivered.compareAndSet(false, true)) {
                return;
            }
            TaskHandle timeoutHandle = timeout.getAndSet(null);
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            TaskHandle retryHandle = worldRetry.getAndSet(null);
            if (retryHandle != null) {
                retryHandle.cancel();
            }
            future.set(null);
            expectedPlayer.set(null);
            activeTeleports.remove(this);
            teleportsByPlayer.remove(playerId, this);
            BiConsumer<Player, Boolean> consumer = callback.getAndSet(null);
            if (consumer != null) {
                safeComplete(consumer, player, success);
            }
        }

        private void abort() {
            if (!delivered.compareAndSet(false, true)) {
                return;
            }
            TaskHandle timeoutHandle = timeout.getAndSet(null);
            if (timeoutHandle != null) {
                timeoutHandle.cancel();
            }
            TaskHandle retryHandle = worldRetry.getAndSet(null);
            if (retryHandle != null) {
                retryHandle.cancel();
            }
            CompletableFuture<Boolean> pending = future.getAndSet(null);
            if (pending != null) {
                pending.cancel(false);
            }
            expectedPlayer.set(null);
            callback.set(null);
            activeTeleports.remove(this);
            teleportsByPlayer.remove(playerId, this);
        }
    }

    private final class TrackedHandle implements TaskHandle {
        private final AtomicReference<TaskHandle> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private TrackedHandle(boolean repeating) {
        }

        private void bind(TaskHandle next) {
            if (next == null) {
                fail();
                return;
            }
            if (next.cancelled()) {
                next.cancel();
                fail();
                return;
            }
            if (cancelRequested.get() || completed.get() || closed.get()) {
                next.cancel();
                fail();
                return;
            }
            delegate.set(next);
            // 处理 bind 与 close/cancel 的并发竞态。
            if (cancelRequested.get() || completed.get() || closed.get()) {
                TaskHandle current = delegate.getAndSet(null);
                if (current != null) {
                    current.cancel();
                }
                fail();
            }
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                // 不长期持有底层 Bukkit/Folia 任务句柄；重复任务在取消或退休后
                // 也必须释放引用，避免 reload/disable 后旧插件实例被保留。
                delegate.set(null);
                activeTasks.remove(this);
            }
        }

        private void fail() {
            cancelRequested.set(true);
            complete();
        }

        /** Marks a Folia entity task as retired (never run) before releasing its delegate. */
        private void retire() {
            cancelRequested.set(true);
            complete();
        }

        @Override
        public void cancel() {
            cancelRequested.set(true);
            TaskHandle current = delegate.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
            complete();
        }

        @Override
        public boolean cancelled() {
            TaskHandle current = delegate.get();
            // A normal one-shot completion releases its delegate, while a
            // Folia retirement calls retire() and sets cancelRequested first.
            // Keep those states distinct so a very short async delay cannot be
            // reported as cancelled merely because it completed before return.
            return cancelRequested.get() || (current != null && current.cancelled());
        }
    }

    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    private static long millisToTicks(long millis) {
        long positive = Math.max(1L, millis);
        // Avoid millis + 49 overflow for callers that pass a very large timeout.
        long ticks = positive / 50L;
        if (positive % 50L != 0L && ticks < Long.MAX_VALUE) {
            ticks++;
        }
        return Math.max(1L, ticks);
    }

    private record FoliaHandle(ScheduledTask task) implements TaskHandle {
        @Override
        public void cancel() {
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public boolean cancelled() {
            return task == null || task.isCancelled();
        }
    }

    private record BukkitHandle(BukkitTask task) implements TaskHandle {
        @Override
        public void cancel() {
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public boolean cancelled() {
            return task == null || task.isCancelled();
        }
    }
}
