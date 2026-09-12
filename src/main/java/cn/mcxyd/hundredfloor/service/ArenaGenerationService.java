package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.game.generator.BlockPlacement;
import cn.mcxyd.hundredfloor.game.generator.ProceduralArenaPlan;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.scheduler.TaskHandle;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Creates and populates generated arenas while keeping all Bukkit world access on its owner context. */
public final class ArenaGenerationService {

    private final Plugin plugin;
    private final ArenaRepository arenas;
    private final TaskScheduler scheduler;
    // 每个 Region 任务只写入有限数量的方块，避免单个区块汇集多层平台时长时间占用 tick。
    private static final int BLOCKS_PER_TASK = 512;
    // 区块任务丢失（例如世界在 Folia 中被卸载）时，必须有上限，不能让生成会话永久挂起。
    private static final long MIN_BATCH_WATCHDOG_TICKS = 20L;
    private static final long MAX_BATCH_WATCHDOG_TICKS = 1_200L;
    // 规划任务和进入 populate 的全局调度都没有返回句柄（TaskScheduler 的
    // runAsync/runGlobal 是 void）；单独的生命周期看门狗用于覆盖这两个调度失败窗口，
    // 避免 generating 永久保留一个无法完成的上下文。
    private static final long GENERATION_LIFECYCLE_WATCHDOG_MILLIS = 120_000L;
    private final ConcurrentMap<String, GenerationContext> generating = new ConcurrentHashMap<>();
    private final Object generationLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ArenaGenerationService(Plugin plugin, ArenaRepository arenas, TaskScheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.arenas = Objects.requireNonNull(arenas, "arenas");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void loadStoredWorlds() {
        if (closed.get()) {
            return;
        }
        try {
            scheduler.runGlobal(() -> arenas.generatedArenas().stream()
                    .filter(ignored -> !closed.get())
                    .map(ArenaRepository.GeneratedArena::definition)
                    .map(ArenaDefinition::waitingSpawn)
                    .map(point -> point.world())
                    .distinct()
                    .forEach(name -> {
                        if (closed.get()) {
                            return;
                        }
                        try {
                            Path worldDirectory = plugin.getServer().getWorldContainer().toPath().resolve(name);
                            if (!Files.isDirectory(worldDirectory)) {
                                // 配置仍在但世界目录已被外部删除时不能悄悄创建一个
                                // 空虚空世界；否则玩家会进入后直接坠落，且管理员
                                // 很难发现地图已经损坏。保留配置并提示重新生成。
                                plugin.getLogger().warning("竞技场世界目录不存在，未创建空地图：" + name);
                                return;
                            }
                            createWorld(name);
                        } catch (RuntimeException exception) {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "无法载入竞技场世界 " + name, exception);
                        }
                    }));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "竞技场世界加载任务无法调度", exception);
        }
    }

    public void generate(String arenaName, int floorCount, long seed, Consumer<GenerationResult> callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            callback.accept(GenerationResult.failure("插件正在关闭，暂时无法生成地图"));
            return;
        }
        if (arenaName == null || arenaName.isBlank()) {
            callback.accept(GenerationResult.failure("竞技场名称不能为空"));
            return;
        }
        String requestedArena = arenaName.trim();
        if (requestedArena.length() > 64 || requestedArena.indexOf('.') >= 0
                || requestedArena.chars().anyMatch(Character::isISOControl)) {
            callback.accept(GenerationResult.failure("竞技场名称过长或包含不支持的字符"));
            return;
        }
        String normalized = requestedArena.toLowerCase(Locale.ROOT);
        if (arenas.find(requestedArena).isPresent()) {
            callback.accept(GenerationResult.failure("竞技场已存在，请先删除旧配置后再生成"));
            return;
        }
        GenerationContext context = new GenerationContext(callback);
        String registrationFailure = null;
        synchronized (generationLock) {
            // close() 与注册必须使用同一把锁，否则关闭遍历后可能又插入一个永远不会清理的会话。
            if (closed.get()) {
                registrationFailure = "插件正在关闭，暂时无法生成地图";
            } else if (generating.putIfAbsent(normalized, context) != null) {
                registrationFailure = "该竞技场正在生成中，请稍候";
            }
        }
        if (registrationFailure != null) {
            callback.accept(GenerationResult.failure(registrationFailure));
            return;
        }
        // 楼层数和生成器 schema 必须参与世界名；否则删除后用同一种子生成
        // 不同楼层的地图会复用旧世界，旧楼层方块会残留到新边界之外。
        String worldName = worldName(requestedArena, seed, floorCount);
        if (closed.get() || context.isCancelledOrCompleted()) {
            complete(normalized, context, GenerationResult.failure("地图生成已取消"));
            return;
        }
        TaskHandle lifecycleWatchdog;
        try {
            lifecycleWatchdog = scheduler.runAsyncDelayed(() -> {
                if (!context.isCancelledOrCompleted()) {
                    context.requestCancellation();
                    complete(normalized, context, GenerationResult.failure("地图生成超时或调度器未响应"));
                }
            }, GENERATION_LIFECYCLE_WATCHDOG_MILLIS);
        } catch (RuntimeException exception) {
            complete(normalized, context,
                    GenerationResult.failure("地图生成看门狗无法调度：" + exception.getMessage()));
            return;
        }
        if (lifecycleWatchdog == null || lifecycleWatchdog.cancelled() || !context.addTask(lifecycleWatchdog)) {
            if (lifecycleWatchdog != null && !lifecycleWatchdog.cancelled()) {
                lifecycleWatchdog.cancel();
            }
            complete(normalized, context, GenerationResult.failure("地图生成看门狗无法调度"));
            return;
        }
        try {
            scheduler.runAsync(() -> {
                if (context.isCancelledOrCompleted()) {
                    complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                    return;
                }
                ProceduralArenaPlan plan;
                try {
                    plan = ProceduralArenaPlan.create(requestedArena, worldName, floorCount, seed);
                } catch (RuntimeException exception) {
                    complete(normalized, context, GenerationResult.failure(exception.getMessage()));
                    return;
                }
                ProceduralArenaPlan prepared = plan;
                if (context.isCancelledOrCompleted() || closed.get()) {
                    complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                    return;
                }
                try {
                    scheduler.runGlobal(() -> populate(normalized, context, prepared));
                } catch (RuntimeException exception) {
                    complete(normalized, context,
                            GenerationResult.failure("地图生成主线程任务无法调度：" + exception.getMessage()));
                }
            });
        } catch (RuntimeException exception) {
            complete(normalized, context,
                    GenerationResult.failure("地图规划任务无法调度：" + exception.getMessage()));
        }
    }

    /** 删除竞技场前取消尚未完成的生成，避免异步保存把刚删除的配置重新写回。 */
    public boolean cancel(String arenaName) {
        if (arenaName == null || arenaName.isBlank()) {
            return false;
        }
        GenerationContext context = generating.get(arenaName.trim().toLowerCase(Locale.ROOT));
        if (context != null) {
            context.requestCancellation();
            complete(arenaName.trim().toLowerCase(Locale.ROOT), context,
                    GenerationResult.failure("地图生成已取消"));
            return true;
        }
        return false;
    }

    /** 插件关闭时取消所有未完成的区块任务，避免旧实例在关闭阶段继续回调。 */
    public void close() {
        List<GenerationContext> contextsToRetire;
        synchronized (generationLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // 先原子摘出索引；closed 已经阻止新的生成注册，旧上下文稍后在锁外退休。
            // 不能在 generationLock 内调用 GenerationContext.retire()：生成完成路径
            // 会先拿 stateLock 再拿 generationLock，若这里反向拿锁会形成死锁。
            contextsToRetire = List.copyOf(generating.values());
            generating.clear();
        }
        // 上下文退休和句柄取消可能触发调度器内部回调，因此全部在锁外执行。
        contextsToRetire.forEach(context -> {
            List<TaskHandle> tasks = context.retire();
            GenerationContext.cancel(tasks);
        });
    }

    private void populate(String normalized, GenerationContext context, ProceduralArenaPlan plan) {
        if (context.isCancelledOrCompleted() || closed.get()) {
            complete(normalized, context, GenerationResult.failure("地图生成已取消"));
            return;
        }
        try {
            createWorld(plan.definition().waitingSpawn().world());
        } catch (RuntimeException exception) {
            complete(normalized, context, GenerationResult.failure("虚空世界创建失败：" + exception.getMessage()));
            return;
        }
        Map<ChunkKey, List<BlockPlacement>> byChunk = new HashMap<>();
        Map<String, Material> materials = new HashMap<>();
        for (BlockPlacement placement : plan.placements()) {
            if (context.isCancelledOrCompleted() || closed.get()) {
                complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                return;
            }
            Material material = materials.computeIfAbsent(placement.materialName(), Material::matchMaterial);
            if (material == null) {
                complete(normalized, context,
                        GenerationResult.failure("未知方块：" + placement.materialName()));
                return;
            }
            byChunk.computeIfAbsent(new ChunkKey(Math.floorDiv(placement.x(), 16),
                    Math.floorDiv(placement.z(), 16)), ignored -> new ArrayList<>()).add(placement);
        }
        List<ChunkBatch> batches = new ArrayList<>();
        for (Map.Entry<ChunkKey, List<BlockPlacement>> entry : byChunk.entrySet()) {
            if (context.isCancelledOrCompleted() || closed.get()) {
                complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                return;
            }
            List<BlockPlacement> placements = entry.getValue();
            for (int start = 0; start < placements.size(); start += BLOCKS_PER_TASK) {
                int end = Math.min(start + BLOCKS_PER_TASK, placements.size());
                batches.add(new ChunkBatch(entry.getKey(), List.copyOf(placements.subList(start, end))));
            }
        }
        Map<String, Material> materialLookup = Map.copyOf(materials);
        AtomicInteger remaining = new AtomicInteger(batches.size());
        AtomicBoolean failed = new AtomicBoolean();
        if (batches.isEmpty()) {
            finishSave(normalized, context, plan);
            return;
        }
        int index = 0;
        for (ChunkBatch batch : batches) {
            if (failed.get() || context.isCancelledOrCompleted() || closed.get()) {
                return;
            }
            ChunkKey key = batch.key();
            // 依次错开批次，令区块修改在多个 tick 内平滑完成。
            long delay = 1L + index++;
            TaskHandle task;
            try {
                task = scheduler.runRegionDelayed(plan.definition().waitingSpawn().world(), key.x, key.z, () -> {
                    if (failed.get() || context.isCancelledOrCompleted() || closed.get()) {
                        return;
                    }
                    try {
                        // Resolve the world inside the owning region instead of retaining a live World
                        // reference inside the cross-region callback.
                        World targetWorld = Bukkit.getWorld(plan.definition().waitingSpawn().world());
                        if (targetWorld == null) {
                            throw new IllegalStateException("竞技场世界已卸载");
                        }
                        targetWorld.getChunkAt(key.x, key.z).load();
                        for (BlockPlacement placement : batch.placements()) {
                            Material material = materialLookup.get(placement.materialName());
                            targetWorld.getBlockAt(placement.x(), placement.y(), placement.z()).setType(material, false);
                        }
                    } catch (RuntimeException exception) {
                        if (failed.compareAndSet(false, true)) {
                            context.cancelTasks();
                            complete(normalized, context,
                                    GenerationResult.failure("方块写入失败：" + exception.getMessage()));
                        }
                        return;
                    }
                    if (remaining.decrementAndGet() == 0 && !failed.get()) {
                        finishSave(normalized, context, plan);
                    }
                }, delay);
            } catch (RuntimeException exception) {
                if (failed.compareAndSet(false, true)) {
                    context.cancelTasks();
                    complete(normalized, context,
                            GenerationResult.failure("地图区块任务无法调度：" + exception.getMessage()));
                }
                return;
            }
            if (task == null) {
                if (failed.compareAndSet(false, true)) {
                    context.cancelTasks();
                    complete(normalized, context, GenerationResult.failure("地图区块任务无法调度"));
                }
                return;
            }
            if (!context.addTask(task)) {
                // 会话可能刚好在调度返回前被取消；addTask 会负责取消这个未登记句柄。
                return;
            }
            if (task.cancelled() && !context.isCancelledOrCompleted() && failed.compareAndSet(false, true)) {
                context.cancelTasks();
                complete(normalized, context, GenerationResult.failure("地图区块任务无法调度"));
                break;
            }
        }

        // Folia 的 RegionScheduler 在目标世界卸载时可能不会执行回调；看门狗确保这类任务最终失败而不是永久占用会话。
        long watchdogDelay = Math.min(MAX_BATCH_WATCHDOG_TICKS,
                Math.max(MIN_BATCH_WATCHDOG_TICKS, batches.size() * 4L + 200L));
        TaskHandle watchdog;
        try {
            watchdog = scheduler.runGlobalDelayed(() -> {
                if (remaining.get() > 0 && !failed.get() && !context.isCancelledOrCompleted()
                        && failed.compareAndSet(false, true)) {
                    context.cancelTasks();
                    complete(normalized, context, GenerationResult.failure("地图区块任务超时或世界已卸载"));
                }
            }, watchdogDelay);
        } catch (RuntimeException exception) {
            if (failed.compareAndSet(false, true)) {
                context.cancelTasks();
                complete(normalized, context,
                        GenerationResult.failure("地图生成看门狗无法调度：" + exception.getMessage()));
            }
            return;
        }
        if (watchdog == null) {
            if (failed.compareAndSet(false, true)) {
                context.cancelTasks();
                complete(normalized, context, GenerationResult.failure("地图生成看门狗无法调度"));
            }
            return;
        }
        if (!context.addTask(watchdog)) {
            return;
        }
        if (watchdog.cancelled() && !context.isCancelledOrCompleted()
                && failed.compareAndSet(false, true)) {
            context.cancelTasks();
            complete(normalized, context, GenerationResult.failure("地图生成看门狗无法调度"));
        }
    }

    private void finishSave(String normalized, GenerationContext context, ProceduralArenaPlan plan) {
        Runnable saveTask = () -> {
            if (context.isCancelledOrCompleted()) {
                complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                return;
            }
            try {
                // 删除命令可能在区块写入期间执行；取消标记后不再把结果写回仓库。
                if (context.isCancelledOrCompleted()) {
                    complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                    return;
                }
                GenerationContext.SaveResult saveResult = context.persistIfActive(
                        () -> arenas.saveGeneratedIfAbsent(plan.definition(), plan.seed()));
                if (saveResult == GenerationContext.SaveResult.INACTIVE) {
                    complete(normalized, context, GenerationResult.failure("地图生成已取消"));
                    return;
                }
                if (saveResult == GenerationContext.SaveResult.CONFLICT) {
                    complete(normalized, context,
                            GenerationResult.failure("竞技场已被其他操作创建，请删除后重新生成"));
                    return;
                }
                complete(normalized, context, GenerationResult.success(plan.definition(), plan.seed()));
            } catch (IOException | RuntimeException exception) {
                complete(normalized, context, GenerationResult.failure("竞技场保存失败：" + exception.getMessage()));
            }
        };
        try {
            scheduler.runAsync(saveTask);
        } catch (RuntimeException exception) {
            complete(normalized, context,
                    GenerationResult.failure("竞技场保存任务无法调度：" + exception.getMessage()));
        }
    }

    private void complete(String normalized, GenerationContext context, GenerationResult result) {
        List<TaskHandle> tasks = context.completeOnce();
        if (tasks == null) {
            return;
        }
        synchronized (generationLock) {
            generating.remove(normalized, context);
        }
        tasks.forEach(task -> {
            if (!task.cancelled()) {
                task.cancel();
            }
        });
        // 关闭期间不再投递玩家/控制台回调；调度器稍后会统一取消剩余任务。
        if (!closed.get() && !context.retired()) {
            try {
                scheduler.runGlobal(() -> context.callback.accept(result));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "地图生成结果回调无法调度", exception);
            }
        }
    }

    private World createWorld(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }
        WorldCreator creator = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(false)
                .generator(new VoidChunkGenerator());
        World created = Bukkit.createWorld(creator);
        if (created == null) {
            throw new IllegalStateException("Bukkit 未返回世界实例");
        }
        return created;
    }

    public static String worldName(String arenaName) {
        if (arenaName == null || arenaName.isBlank()) {
            throw new IllegalArgumentException("竞技场名称不能为空");
        }
        String canonical = arenaName.trim().toLowerCase(Locale.ROOT);
        String normalized = canonical.replaceAll("[^a-z0-9_-]", "_");
        String readable = normalized.replaceAll("_+", "_");
        if (readable.isBlank() || readable.equals("_")) {
            readable = "arena";
        }
        String suffix = java.util.UUID.nameUUIDFromBytes(
                canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12);
        int readableLimit = Math.max(1, 48 - "hfr_".length() - 1 - suffix.length());
        if (readable.length() > readableLimit) {
            readable = readable.substring(0, readableLimit);
        }
        return "hfr_" + readable + "_" + suffix;
    }

    public static String worldName(String arenaName, long seed) {
        String base = worldName(arenaName);
        String suffix = Long.toUnsignedString(seed, 36);
        String result = base + "_" + suffix;
        return result.length() > 63 ? result.substring(0, 63) : result;
    }

    /** 生成布局版本隔离名；保留旧重载仅用于兼容已有配置和测试。 */
    public static String worldName(String arenaName, long seed, int floorCount) {
        if (floorCount < 1 || floorCount > 100) {
            throw new IllegalArgumentException("楼层数量必须在 1-100 之间");
        }
        String base = worldName(arenaName);
        String key = arenaName.trim().toLowerCase(Locale.ROOT) + "|" + seed + "|" + floorCount + "|schema-2";
        String suffix = java.util.UUID.nameUUIDFromBytes(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 20).toLowerCase(Locale.ROOT);
        String result = base + "_" + suffix;
        return result.length() > 63 ? result.substring(0, 63) : result;
    }

    public record GenerationResult(boolean success, ArenaDefinition definition, long seed, String reason) {
        public static GenerationResult success(ArenaDefinition definition, long seed) {
            return new GenerationResult(true, definition, seed, "");
        }

        public static GenerationResult failure(String reason) {
            return new GenerationResult(false, null, 0, Objects.requireNonNullElse(reason, "未知错误"));
        }
    }

    private record ChunkKey(int x, int z) {
    }

    private record ChunkBatch(ChunkKey key, List<BlockPlacement> placements) {
    }

    // 包可见仅用于无 Bukkit 环境的并发单元测试；生产代码仍只通过外层服务管理会话。
    static final class GenerationContext {
        private final Consumer<GenerationResult> callback;
        private final Object stateLock = new Object();
        private final List<TaskHandle> tasks = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();

        GenerationContext(Consumer<GenerationResult> callback) {
            this.callback = callback;
        }

        boolean isCancelledOrCompleted() {
            return cancelled.get() || completed.get();
        }

        boolean retired() {
            return retired.get();
        }

        /**
         * Registers a task atomically with cancellation/completion. A scheduler may return a handle
         * immediately before another thread cancels this generation; in that case the new handle is
         * cancelled outside the lock and never leaks beyond the generation lifecycle.
         */
        boolean addTask(TaskHandle task) {
            Objects.requireNonNull(task, "task");
            boolean cancelNow;
            synchronized (stateLock) {
                cancelNow = cancelled.get() || completed.get();
                if (!cancelNow) {
                    tasks.add(task);
                }
            }
            if (cancelNow) {
                task.cancel();
                return false;
            }
            return true;
        }

        void requestCancellation() {
            List<TaskHandle> snapshot;
            synchronized (stateLock) {
                cancelled.set(true);
                snapshot = List.copyOf(tasks);
            }
            cancel(snapshot);
        }

        void cancelTasks() {
            List<TaskHandle> snapshot;
            synchronized (stateLock) {
                snapshot = List.copyOf(tasks);
            }
            cancel(snapshot);
        }

        List<TaskHandle> completeOnce() {
            synchronized (stateLock) {
                if (!completed.compareAndSet(false, true)) {
                    return null;
                }
                List<TaskHandle> snapshot = List.copyOf(tasks);
                tasks.clear();
                return snapshot;
            }
        }

        List<TaskHandle> retire() {
            synchronized (stateLock) {
                cancelled.set(true);
                completed.set(true);
                retired.set(true);
                List<TaskHandle> snapshot = List.copyOf(tasks);
                tasks.clear();
                return snapshot;
            }
        }

        /**
         * 将“仍处于活动状态”检查与最终磁盘提交放在同一状态锁内。
         * cancel/delete 在异步线程等待此临界区结束后，就能确定旧生成不会再次写回配置。
         */
        SaveResult persistIfActive(PersistenceAction action) throws IOException {
            synchronized (stateLock) {
                if (cancelled.get() || completed.get()) {
                    return SaveResult.INACTIVE;
                }
                return action.save() ? SaveResult.SAVED : SaveResult.CONFLICT;
            }
        }

        enum SaveResult {
            SAVED,
            CONFLICT,
            INACTIVE
        }

        @FunctionalInterface
        interface PersistenceAction {
            boolean save() throws IOException;
        }

        private static void cancel(List<TaskHandle> handles) {
            handles.forEach(handle -> {
                try {
                    if (handle != null && !handle.cancelled()) {
                        handle.cancel();
                    }
                } catch (RuntimeException ignored) {
                    // 单个第三方句柄取消失败不能阻止同一生成会话的其它任务释放。
                }
            });
        }
    }

    private static final class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
            // Intentionally empty: the arena is a controlled void world and all blocks are explicit placements.
        }
    }
}
