package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.ConfigurationManager;
import cn.mcxyd.hundredfloor.config.GameConfig;
import cn.mcxyd.hundredfloor.config.IdentityMode;
import cn.mcxyd.hundredfloor.config.JoinNpcConfig;
import cn.mcxyd.hundredfloor.game.ArenaRuntime;
import cn.mcxyd.hundredfloor.game.ArenaValidator;
import cn.mcxyd.hundredfloor.game.FinishReason;
import cn.mcxyd.hundredfloor.game.FloorProgressCalculator;
import cn.mcxyd.hundredfloor.game.JoinResult;
import cn.mcxyd.hundredfloor.game.MatchPhase;
import cn.mcxyd.hundredfloor.game.MatchView;
import cn.mcxyd.hundredfloor.game.MovementPoint;
import cn.mcxyd.hundredfloor.game.ValidationResult;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import cn.mcxyd.hundredfloor.scheduler.TaskHandle;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class GameService {

    /** 登录恢复传送最长等待时间；必须长于调度器内部传送超时，避免旧传送越过状态闸门。 */
    private static final long LOGIN_RESTORE_TIMEOUT_MILLIS = 20_000L;
    /** 结束会话后实体调度可能因玩家离线/区域退休而不回调；用全局看门狗兜底释放 UUID 状态。 */
    private static final long END_SESSION_CLEANUP_TIMEOUT_TICKS = 200L;
    /** 业务传送看门狗必须长于调度器的 15 秒异步传送兜底。 */
    private static final long TELEPORT_WATCHDOG_TICKS = 400L;
    /** 起点传送包含等待区传送的最坏情况，超过此时间就按 UUID 清理，避免幽灵参赛者。 */
    private static final long START_TELEPORT_TIMEOUT_TICKS = TELEPORT_WATCHDOG_TICKS;

    private final ConfigurationManager configuration;
    private final ArenaRepository arenas;
    private final RecordRepository records;
    private final RecoveryRepository recoveries;
    private final MessageService messages;
    private final TaskScheduler scheduler;
    private final ProxyService proxy;
    private final LobbyItemService lobbyItems;
    private final ArenaValidator validator = new ArenaValidator();
    private final FloorProgressCalculator progressCalculator = new FloorProgressCalculator();
    private final ConcurrentMap<String, ArenaSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerSession> players = new ConcurrentHashMap<>();
    /** 正在执行本地位置恢复的玩家，短时间内禁止重新加入新局，避免旧传送回调覆盖新会话。 */
    private final ConcurrentMap<UUID, PlayerSession> pendingRestores = new ConcurrentHashMap<>();
    /** 正在执行登录恢复的玩家；避免恢复传送回调覆盖刚加入的新比赛。 */
    private final ConcurrentMap<UUID, LoginRestore> loginRestores = new ConcurrentHashMap<>();
    private final Object sessionLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public GameService(ConfigurationManager configuration, ArenaRepository arenas, RecordRepository records,
                       RecoveryRepository recoveries,
                       MessageService messages, TaskScheduler scheduler,
                       ProxyService proxy, LobbyItemService lobbyItems) {
        this.configuration = configuration;
        this.arenas = arenas;
        this.records = records;
        this.recoveries = recoveries;
        this.messages = messages;
        this.scheduler = scheduler;
        this.proxy = proxy;
        this.lobbyItems = lobbyItems;
    }

    public void join(Player player, String arenaName) {
        if (shuttingDown.get()) {
            return;
        }
        if (player == null || arenaName == null || arenaName.isBlank()) {
            if (player != null) {
                messages.send(player, "arena-missing", Map.of("arena", String.valueOf(arenaName)));
            }
            return;
        }
        ArenaDefinition arena = arenas.find(arenaName).orElse(null);
        if (arena == null) {
            messages.send(player, "arena-missing", Map.of("arena", arenaName));
            return;
        }
        ValidationResult validation = arena.floorGates().size() >= 1 && arena.floorGates().size() <= 100
                ? validator.validate(arena, arena.floorGates().size())
                : ValidationResult.invalid("楼层数量必须在 1-100 之间");
        if (!validation.valid()) {
            messages.send(player, "arena-invalid", Map.of("reason", validation.reason()));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (players.containsKey(playerId) || pendingRestores.containsKey(playerId)
                || loginRestores.containsKey(playerId)) {
            messages.send(player, "already-playing", Map.of());
            return;
        }
        // 等待区必须能安全放置固定在第 9 格的返回大厅道具；提前拒绝满背包
        // 玩家，避免加入成功后没有返回入口而被困在小游戏子服。
        if (!lobbyItems.canGive(player)) {
            messages.send(player, "lobby-item-inventory-full", Map.of());
            return;
        }
        ArenaSession session = acquireSession(arena);
        if (session == null) {
            // 配置刚被保存/删除且旧局仍在使用，禁止新旧坐标混用。
            messages.send(player, "arena-reloading", Map.of("arena", arena.name()));
            return;
        }
        ArenaPoint originalPoint;
        try {
            originalPoint = snapshot(player.getLocation());
        } catch (RuntimeException exception) {
            pluginLog("无法记录玩家原始位置，已拒绝加入：" + exception.getMessage(), exception);
            messages.send(player, "teleport-failed", Map.of());
            cleanupSessionIfIdle(session);
            return;
        }
        PlayerSession playerSession = new PlayerSession(player, playerId, arena.name(), session,
                originalPoint, player.getGameMode(), safeHealth(player), safeFoodLevel(player));
        long now = System.currentTimeMillis();
        JoinResult result = null;
        boolean duplicatePlayer = false;
        boolean rejectedSession = false;
        // 与空会话回收使用同一把锁，避免出现“先拿到空 session、回收线程随后
        // 移除、最后才 runtime.join”的竞态；该竞态会生成没有 tick/end 流程的幽灵局。
        synchronized (sessionLock) {
            String sessionKey = normalizeArenaName(arena.name());
            if (shuttingDown.get() || sessions.get(sessionKey) != session
                    || session.retired() || session.ended()) {
                rejectedSession = true;
            } else if (loginRestores.containsKey(playerId)) {
                // 与登录恢复登记共用 sessionLock，保证“恢复闸门”和新一局
                // 的 PlayerSession 不会在两个线程之间交叉成立。
                duplicatePlayer = true;
            } else if (players.putIfAbsent(playerId, playerSession) != null) {
                duplicatePlayer = true;
            } else {
                result = session.runtime.join(playerId, now);
            }
        }
        if (rejectedSession) {
            messages.send(player, "arena-reloading", Map.of("arena", arena.name()));
            cleanupSessionIfIdle(session);
            return;
        }
        if (duplicatePlayer) {
            messages.send(player, "already-playing", Map.of());
            cleanupSessionIfIdle(session);
            return;
        }
        // 加入和离场可能由不同的事件回调并发触发；若离场已经先清理了
        // PlayerSession，撤销本次 runtime 加入，避免留下幽灵玩家占用名额。
        if (result == JoinResult.JOINED && playerSession.cleared()) {
            session.runtime.leave(playerId);
            cleanupSessionIfIdle(session);
            return;
        }
        if (shuttingDown.get()) {
            if (result == JoinResult.JOINED) {
                clearAndRemember(playerId, playerSession, session);
            }
            cleanupSessionIfIdle(session);
            return;
        }
        if (result != JoinResult.JOINED) {
            players.remove(playerId, playerSession);
            // putIfAbsent 成功后 runtime.join 仍可能观察到同一 UUID（例如
            // 旧会话清理与本次加入交错）。本次 PlayerSession 不属于 runtime，
            // 但 ALREADY_JOINED 分支中的 UUID 已经被旧状态占用；若不回滚，
            // 会留下没有对应 PlayerSession 的幽灵参赛者并永久占用名额。
            if (result == JoinResult.ALREADY_JOINED) {
                session.runtime.leave(playerId);
            }
        }
        switch (result) {
            case JOINED -> {
                playerSession.waitingTeleportPending(true);
                long waitingTeleportToken = playerSession.beginTeleport();
                messages.send(player, "joined", Map.of("arena", arena.name()));
                // 传送回调可能因玩家离线或区域退休而丢失；全局 UUID 看门狗
                // 不读取 Player/Inventory，确保不会留下占位幽灵局。
                TaskHandle joinTimeout = scheduleGlobalDelayed(() -> {
                    if (!playerSession.isTeleportCurrent(waitingTeleportToken)
                            || !playerSession.waitingTeleportPending()
                            || !isCurrentPlayer(playerId, playerSession, session)) {
                        return;
                    }
                    playerSession.waitingTeleportPending(false);
                    playerSession.invalidateTeleport();
                    if (clearAndRemember(playerId, playerSession, session)) {
                        // 清理已经在全局上下文完成；背包只能回到玩家实体上下文
                        // 清理，且不会把消息/清理动作施加到同 UUID 的新会话。
                        scheduleAfterUuidCleanup(playerId, "teleport-failed");
                    }
                }, TELEPORT_WATCHDOG_TICKS);
                if (!usable(joinTimeout)) {
                    playerSession.waitingTeleportPending(false);
                    playerSession.invalidateTeleport();
                    if (clearAndRemember(playerId, playerSession, session)) {
                        scheduleAfterUuidCleanup(playerId, "teleport-failed");
                    }
                    return;
                }
                playerSession.joinTimeout(joinTimeout);
                teleport(player, arena.waitingSpawn(), (target, success) -> {
                    if (!playerSession.isTeleportCurrent(waitingTeleportToken)) {
                        return;
                    }
                    playerSession.waitingTeleportPending(false);
                    playerSession.cancelJoinTimeout();
                    boolean currentPlayer = isCurrentPlayer(playerId, playerSession, session);
                    if (!currentPlayer) {
                        return;
                    }
                    if (!success) {
                        boolean cleared = target == null
                                ? clearAndRemember(playerId, playerSession, session)
                                : clearAndRemember(target, playerSession, session);
                        if (cleared && target != null) {
                            messages.send(target, "teleport-failed", Map.of());
                        }
                    } else {
                        // 传送完成可能晚于比赛开始；运行中绝不能重新发放返回道具。
                        MatchPhase phase = session.runtime.view(System.currentTimeMillis()).phase();
                        if (phase == MatchPhase.WAITING || phase == MatchPhase.COUNTDOWN) {
                            if (target == null || !lobbyItems.give(target)) {
                                // 极少数第三方背包插件可能在传送期间改变库存；
                                // 发放失败时立即退出本局，避免留下无返回入口的会话。
                                if (target != null) {
                                    removePlayer(target, playerSession, true, session, false);
                                } else {
                                    clearAndRemember(playerId, playerSession, session);
                                }
                            }
                        } else if (target != null) {
                            lobbyItems.removeAll(target);
                            if (phase == MatchPhase.RUNNING) {
                                beginStartTeleport(target, playerSession, session);
                            }
                        }
                    }
                });
                if (session.runtime.view(now).phase() == MatchPhase.COUNTDOWN) {
                    messages.send(player, "countdown-started", Map.of("seconds", session.countdownSeconds));
                }
            }
            case ALREADY_JOINED -> messages.send(player, "already-playing", Map.of());
            case FULL -> messages.send(player, "arena-full", Map.of());
            case RUNNING -> messages.send(player, "game-running", Map.of());
        }
        cleanupSessionIfIdle(session);
    }

    /** 判断实体是否是配置的入场 NPC；调用方必须处于该实体的交互事件上下文。 */
    public boolean isJoinNpc(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            GameConfig gameConfig = configuration.game();
            JoinNpcConfig npc = gameConfig == null ? null : gameConfig.joinNpc();
            if (npc == null || !npc.enabled() || entity.getType() != npc.entityType()) {
                return false;
            }
            if (entity.customName() == null) {
                return false;
            }
            String actualName = PlainTextComponentSerializer.plainText()
                    .serialize(entity.customName()).trim();
            return actualName.equals(npc.name());
        } catch (RuntimeException exception) {
            // 实体可能在 Folia 区域退休或配置正在重载；NPC 识别失败不应
            // 让交互事件线程向核心抛出异常。
            pluginLog("识别入场 NPC 失败：" + exception.getMessage(), exception);
            return false;
        }
    }

    /** 由 NPC 点击事件调用；只传递不可变的玩家上下文，不从异步线程读取实体。 */
    public void joinFromNpc(Player player) {
        if (player == null) {
            return;
        }
        if (!player.hasPermission("hundredfloorrush.command.join")) {
            messages.send(player, "no-permission", Map.of());
            return;
        }
        JoinNpcConfig npc = configuration.game().joinNpc();
        if (!npc.arena().isBlank()) {
            join(player, npc.arena());
            return;
        }
        var arenasAvailable = arenas.all().stream().toList();
        if (arenasAvailable.size() == 1) {
            join(player, arenasAvailable.get(0).name());
            return;
        }
        messages.send(player, arenasAvailable.isEmpty() ? "join-npc-no-arena" : "join-npc-multiple-arenas", Map.of());
    }

    public void leave(Player player) {
        if (player == null) {
            return;
        }
        PlayerSession session = players.get(player.getUniqueId());
        if (session == null) {
            messages.send(player, "not-playing", Map.of());
            return;
        }
        removePlayer(player, session, true);
    }

    /** 由返回大厅道具调用；事件已经在玩家实体所有者上下文。 */
    public void returnToLobby(Player player) {
        if (player == null) {
            return;
        }
        PlayerSession session = players.get(player.getUniqueId());
        if (session == null) {
            lobbyItems.removeAll(player);
            messages.send(player, "not-playing", Map.of());
            return;
        }
        removePlayer(player, session, true);
    }

    public boolean isPlaying(UUID playerId) {
        return playerId != null && players.containsKey(playerId);
    }

    public void refreshProxyRegistration() {
        proxy.refreshRegistration();
    }

    public void start(String arenaName) {
        String key = normalizeArenaName(arenaName);
        ArenaSession session = sessions.get(key);
        if (session == null) {
            messages.send(Bukkit.getConsoleSender(), "arena-missing", Map.of("arena", String.valueOf(arenaName)));
            return;
        }
        ArenaDefinition current = arenas.find(arenaName).orElse(null);
        if (current == null || !session.matches(current) || session.retired()) {
            session.retire();
            cleanupSessionIfIdle(session);
            messages.send(Bukkit.getConsoleSender(), "arena-reloading", Map.of("arena", String.valueOf(arenaName)));
            return;
        }
        if (session.runtime.forceStart(System.currentTimeMillis())) {
            startRunning(session);
        }
    }

    public void tick() {
        if (shuttingDown.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ArenaSession session : sessions.values()) {
            // shutdown() 可以在全局 tick 已经开始后并发设置标志并清空会话；
            // 循环内再次检查，避免关闭阶段继续推进旧 runtime 或提交传送任务。
            if (shuttingDown.get()) {
                break;
            }
            try {
                reconcileArenaDefinition(session);
                if (shuttingDown.get()) {
                    break;
                }
                session.runtime.tick(now);
                MatchView view = session.runtime.view(now);
                // view()/finish()/其他玩家实体事件也可能在全局 tick 之间推进
                // 倒计时；不能只依赖“previous != RUNNING”的边沿，否则全局
                // tick 看到的 previous 已经是 RUNNING 时会漏掉起点传送。
                if (view.phase() == MatchPhase.RUNNING && !session.started()) {
                    startRunning(session);
                }
                if (view.phase() == MatchPhase.FINISHED && !session.ended()) {
                    scheduleEndSession(session, view.finishReason());
                }
                cleanupSessionIfIdle(session);
            } catch (RuntimeException exception) {
                // 单个竞技场状态异常不能中断全局 tick，否则其它竞技场会一起
                // 停止倒计时/超时处理。保留会话供下一 tick 重试，并把完整堆栈
                // 写入控制台，玩家只会看到正常的业务消息。
                pluginLog("推进竞技场状态失败：" + session.arena.name(), exception);
            }
        }
    }

    public void onMove(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) {
            return;
        }
        PlayerSession playerSession = players.get(player.getUniqueId());
        if (playerSession == null || !playerSession.isExpectedPlayer(player)) {
            return;
        }
        ArenaSession session = playerSession.arenaSession();
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)
                || playerSession.finished()
                || session.runtime.view(System.currentTimeMillis()).phase() != MatchPhase.RUNNING) {
            return;
        }
        if (from.getWorld() == null || to.getWorld() == null
                || !session.arena.bounds().world().equals(from.getWorld().getName())
                || !session.arena.bounds().world().equals(to.getWorld().getName())
                // 先验证移动线段两端都在赛道内，再计算楼层穿越；否则玩家可以从
                // 边界外穿过入口并在恢复前刷出虚假的楼层进度。
                || !session.arena.bounds().contains(from.getWorld().getName(), from.getX(), from.getY(), from.getZ())
                || !session.arena.bounds().contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            recover(player, playerSession, session);
            return;
        }
        MovementPoint previous = new MovementPoint(from.getX(), from.getY(), from.getZ());
        MovementPoint current = new MovementPoint(to.getX(), to.getY(), to.getZ());
        int progress = progressCalculator.advance(session.arena.floorGates(), playerSession.completedFloors(),
                previous, current, session.gateRadius);
        if (progress > playerSession.completedFloors()) {
            playerSession.completedFloors(progress);
            messages.send(player, "floor-passed", Map.of("floor", progress, "total", session.arena.floorGates().size()));
        }
        ArenaPoint finish = session.arena.finishPoint();
        double dx = to.getX() - finish.x();
        double dy = to.getY() - finish.y();
        double dz = to.getZ() - finish.z();
        if (playerSession.completedFloors() >= session.arena.floorGates().size()
                && dx * dx + dy * dy + dz * dz <= session.finishRadius * session.finishRadius) {
            finish(player, playerSession, session);
        }
    }

    public void onDeath(PlayerDeathEvent event) {
        if (event == null || event.getEntity() == null) {
            return;
        }
        Player player = event.getEntity();
        PlayerSession playerSession = players.get(player.getUniqueId());
        if (playerSession == null || !playerSession.isExpectedPlayer(player)
                || !isCurrentPlayer(player.getUniqueId(), playerSession, playerSession.arenaSession())) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        // 常规致命伤害会在 EntityDamageEvent 阶段被拦截；这里保留给 /kill 等绕过伤害事件的路径。
        schedulePostDeathRecovery(player, playerSession, playerSession.arenaSession(), 0);
    }

    /**
     * 在死亡发生前拦截致命伤害，避免依赖 Folia 没有安全替代路径的重生事件。
     * 调用方必须位于玩家实体事件上下文。
     */
    public boolean preventFatalDamage(EntityDamageEvent event) {
        if (event == null) {
            return false;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return false;
        }
        PlayerSession playerSession = players.get(player.getUniqueId());
        if (playerSession == null || !playerSession.isExpectedPlayer(player)) {
            return false;
        }
        ArenaSession session = playerSession.arenaSession();
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)) {
            return false;
        }
        MatchPhase phase = session.runtime.view(System.currentTimeMillis()).phase();
        // 已完成的选手和结果展示阶段玩家仍属于本局。直接放行伤害会让他们在
        // 等待切服期间死亡，而后续恢复又可能因比赛已结束而无法执行。
        if (playerSession.finished() || phase == MatchPhase.FINISHED) {
            event.setCancelled(true);
            stabilizeAfterDamage(player);
            scheduler.runEntityDelayed(player, () -> recover(player, playerSession, session, true), 1);
            return true;
        }
        if (event.getFinalDamage() < player.getHealth() + player.getAbsorptionAmount()) {
            return false;
        }
        event.setCancelled(true);
        // 取消致命伤害后立即解除残血/着火状态，避免同一 tick 的火焰或摔落事件
        // 再次触发死亡；真正的安全位置仍由下一 tick 的实体传送完成。
        stabilizeAfterDamage(player);
        scheduler.runEntityDelayed(player, () -> recover(player, playerSession, session, true), 1);
        return true;
    }

    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        PlayerSession session = players.get(playerId);
        if (session != null && session.isExpectedPlayer(event.getPlayer())) {
            recoveries.remember(session.playerId(), session.originalPoint());
            saveRecoveriesAsync("无法保存掉线恢复记录：");
            // 玩家已退出实体上下文，不再调用 isOnline()/Inventory API；只用 UUID
            // 清理会话，避免 Folia 区域退休后访问已失效的 Player 实例。
            clearPlayerStateById(playerId, session, session.arenaSession());
        }
        PlayerSession pending = pendingRestores.get(playerId);
        if (pending != null && pending.isExpectedPlayer(event.getPlayer())) {
            pendingRestores.remove(playerId, pending);
        } else {
            pending = null;
        }
        if (pending != null) {
            // 退出会让原 Player 实例失效；仅取消超时不足以阻止已经提交的
            // teleportAsync 回调继续到达。先推进 token，旧回调随后只能被忽略。
            pending.invalidateTeleport();
            pending.cancelRestoreTimeout();
        }
        LoginRestore loginRestore = loginRestores.get(playerId);
        if (loginRestore != null && loginRestore.isExpected(event.getPlayer())) {
            loginRestores.remove(playerId, loginRestore);
        } else {
            loginRestore = null;
        }
        if (loginRestore != null) {
            loginRestore.invalidateTeleport();
            loginRestore.cancelTimeout();
        }
    }

    public void onJoin(Player player) {
        if (player == null || shuttingDown.get()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        // 某些代理/核心路径可能没有送达上一次 PlayerQuitEvent。先用 UUID
        // 快照摘除残留会话，避免旧比赛继续占用名额或把结果写给新实体。
        PlayerSession staleSession = players.get(playerId);
        if (staleSession != null && !staleSession.isExpectedPlayer(player)
                && clearPlayerStateById(playerId, staleSession, staleSession.arenaSession())) {
            recoveries.remember(playerId, staleSession.originalPoint());
            saveRecoveriesAsync("无法保存残留会话恢复记录：");
        }
        // 玩家可能在上一次离场的本地恢复传送尚未回调前就重新连接。
        // 先移除旧的 pending 状态并取消其超时句柄，否则旧回调可能只取消超时
        // 却无法通过旧 Player 实例完成清理，最终把新登录永久挡在 already-playing。
        PlayerSession staleRestore = pendingRestores.remove(playerId);
        if (staleRestore != null) {
            staleRestore.invalidateTeleport();
            staleRestore.cancelRestoreTimeout();
        }
        // 登录恢复同样需要一个 UUID 级别的闸门。否则玩家在恢复传送完成前
        // 执行 /hfr join，旧回调可能把新比赛中的玩家再次传回原位置。
        if (recoveries.find(playerId).isPresent()) {
            LoginRestore restore = new LoginRestore(player);
            LoginRestoreRegistration registration = registerLoginRestore(playerId, restore, true);
            if (!registration.accepted()) {
                // 玩家可能已经在加入新局或本插件正在关闭；不要登记一个
                // 没有对应传送流程的永久恢复闸门。
                return;
            }
            LoginRestore previous = registration.previous();
            if (previous != null) {
                previous.invalidateTeleport();
                previous.cancelTimeout();
            }
            TaskHandle restoreTimeout = scheduler.runAsyncDelayed(() -> {
                completeLoginRestore(playerId, restore);
            }, LOGIN_RESTORE_TIMEOUT_MILLIS);
            if (restoreTimeout == null || restoreTimeout.cancelled()) {
                completeLoginRestore(playerId, restore);
                return;
            }
            restore.timeout(restoreTimeout);
            scheduler.runPlayer(playerId, target -> {
                if (shuttingDown.get() || !target.isOnline()
                        || loginRestores.get(playerId) != restore
                        || !restore.isExpected(target)) {
                    completeLoginRestore(playerId, restore);
                    return;
                }
                // 道具清理必须和其它玩家背包操作一起留在实体所有者上下文。
                lobbyItems.removeAll(target);
                Optional<ArenaPoint> recoveryPoint = recoveries.find(playerId);
                if (recoveryPoint.isEmpty()) {
                    completeLoginRestore(playerId, restore);
                    return;
                }
                long teleportToken = restore.beginTeleport();
                teleport(player, recoveryPoint.get(), (restored, success) -> {
                    if (!restore.isTeleportCurrent(teleportToken)
                            || loginRestores.get(playerId) != restore) {
                        return;
                    }
                    completeLoginRestore(playerId, restore);
                    if (restored == null || !restore.isExpected(restored)) {
                        return;
                    }
                    if (success) {
                        recoveries.forget(restored.getUniqueId());
                        scheduler.runAsync(() -> saveRecoveries("无法清理登录恢复记录："));
                        messages.send(restored, "login-recovered", Map.of());
                    } else {
                        messages.send(restored, "teleport-failed", Map.of());
                    }
                });
            });
            return;
        }
        // PlayerJoinEvent 的官方契约禁止在事件回调内直接传送；同时 Folia 下事件
        // 不一定运行在玩家实体 Region。延迟进入 UUID 对应的实体上下文后再读写
        // Player/Inventory，并在该上下文发起异步传送。
        scheduler.runPlayer(playerId, target -> {
            if (shuttingDown.get() || !target.isOnline()) {
                return;
            }
            // 加入命令可能在 PlayerJoinEvent 的延迟实体任务之前完成；
            // 此处必须在同一把 sessionLock 下登记恢复闸门，避免旧恢复传送
            // 把已经进入新比赛的玩家拉回原位置。
            if (players.containsKey(playerId) || pendingRestores.containsKey(playerId)
                    || loginRestores.containsKey(playerId)) {
                return;
            }
            // 断线重连后玩家不再拥有旧比赛会话，清理残留道具避免跨服携带或重复触发。
            lobbyItems.removeAll(target);
            recoveries.find(playerId).ifPresent(point -> {
                LoginRestore restore = new LoginRestore(target);
                if (!registerLoginRestore(playerId, restore, false).accepted()) {
                    return;
                }
                TaskHandle restoreTimeout = scheduler.runAsyncDelayed(
                        () -> completeLoginRestore(playerId, restore), LOGIN_RESTORE_TIMEOUT_MILLIS);
                if (restoreTimeout == null || restoreTimeout.cancelled()) {
                    completeLoginRestore(playerId, restore);
                    return;
                }
                restore.timeout(restoreTimeout);
                long teleportToken = restore.beginTeleport();
                teleport(target, point, (restored, success) -> {
                        if (!restore.isTeleportCurrent(teleportToken)
                                || loginRestores.get(playerId) != restore
                                || restored == null || !restore.isExpected(restored)) {
                            completeLoginRestore(playerId, restore);
                            return;
                        }
                        completeLoginRestore(playerId, restore);
                        if (success) {
                            recoveries.forget(restored.getUniqueId());
                            scheduler.runAsync(() -> saveRecoveries("无法清理登录恢复记录："));
                            messages.send(restored, "login-recovered", Map.of());
                        } else {
                            messages.send(restored, "teleport-failed", Map.of());
                        }
                    });
            });
        });
    }

    public Optional<MatchView> view(String arenaName) {
        ArenaSession session = sessions.get(normalizeArenaName(arenaName));
        return session == null ? Optional.empty() : Optional.of(session.runtime.view(System.currentTimeMillis()));
    }

    public void reloadMessages() {
        messages.replace(configuration.messages());
    }

    public ValidationResult validate(ArenaDefinition arena) {
        return validator.validate(arena, configuration.game().floorCount());
    }

    public String identity(Player player) {
        if (player == null) {
            return "";
        }
        GameConfig config = configuration.game();
        return identity(player, config.identityMode(), config.offlineNameIgnoreCase());
    }

    private String identity(Player player, ArenaSession session) {
        return identity(player, session.identityMode, session.offlineNameIgnoreCase);
    }

    private static String identity(Player player, IdentityMode mode, boolean ignoreCase) {
        if (player == null || mode == null) {
            return "";
        }
        if (mode == IdentityMode.ONLINE_UUID) {
            return player.getUniqueId().toString();
        }
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return "";
        }
        return ignoreCase ? name.toLowerCase(java.util.Locale.ROOT) : name;
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        // 禁用阶段不跨区域读取在线 Player，只保存不可变的会话快照供下次登录恢复。
        // 先在 sessionLock 下取得快照，和 join 的“登记玩家 + runtime.join”事务
        // 串行化，避免关闭期间刚加入的玩家漏掉恢复坐标。
        List<PlayerSession> playerSnapshot;
        List<ArenaSession> sessionSnapshot;
        synchronized (sessionLock) {
            playerSnapshot = List.copyOf(players.values());
            sessionSnapshot = List.copyOf(sessions.values());
            sessions.clear();
        }
        playerSnapshot.forEach(session -> recoveries.remember(session.playerId(), session.originalPoint()));
        // 取消句柄和 runtime 不在锁内执行，避免第三方调度器在 cancel 时同步
        // 回调而形成 sessionLock 的锁循环。
        sessionSnapshot.forEach(session -> {
            session.retire();
            TaskHandle endTask = session.endTask();
            if (endTask != null) {
                endTask.cancel();
            }
            session.runtime.cancel();
        });
        players.clear();
        pendingRestores.values().forEach(PlayerSession::cancelRestoreTimeout);
        pendingRestores.clear();
        loginRestores.values().forEach(LoginRestore::cancelTimeout);
        loginRestores.clear();
        // 禁用/重载时旧返回道具不能因为会话表清空而失去保护。只传递 UUID
        // 进入实体所有者上下文，不在关闭线程直接访问 PlayerInventory。
        playerSnapshot.forEach(session -> {
            try {
                scheduler.runPlayer(session.playerId(), player -> {
                    if (!players.containsKey(session.playerId())
                            && !pendingRestores.containsKey(session.playerId())
                            && !loginRestores.containsKey(session.playerId())) {
                        lobbyItems.removeAll(player);
                    }
                });
            } catch (RuntimeException exception) {
                pluginLog("关闭阶段清理返回大厅道具任务调度失败：" + exception.getMessage(), exception);
            }
        });
        try {
            recoveries.save();
        } catch (Exception exception) {
            pluginLog("无法保存掉线恢复记录：" + exception.getMessage(), exception);
        }
    }

    private ArenaSession newSession(ArenaDefinition arena) {
        GameConfig config = configuration.game();
        return new ArenaSession(arena, new ArenaRuntime(arena.name(), config.minimumPlayers(),
                config.maximumPlayers(), config.countdownSeconds(), config.timeLimitSeconds()),
                config.countdownSeconds(), config.resultDisplaySeconds(), config.gateRadius(), config.finishRadius(),
                config.recoveryCooldownSeconds(), config.finishFirework(), config.identityMode(),
                config.saveLastKnownName(), config.offlineNameIgnoreCase());
    }

    /**
     * 获取与当前竞技场定义绑定的会话。保存/删除竞技场后，旧会话只允许当前玩家完成，
     * 不会把新坐标混入旧比赛；旧会话为空时立即回收并建立新会话。
     */
    private ArenaSession acquireSession(ArenaDefinition arena) {
        String key = normalizeArenaName(arena.name());
        synchronized (sessionLock) {
            if (shuttingDown.get()) {
                return null;
            }
            ArenaSession existing = sessions.get(key);
            if (existing != null) {
                if (existing.matches(arena) && !existing.retired() && !existing.ended()) {
                    return existing;
                }
                if (!existing.runtime.isEmpty()) {
                    existing.retire();
                    return null;
                }
                removeSessionLocked(key, existing);
            }
            ArenaSession created = newSession(arena);
            sessions.put(key, created);
            return created;
        }
    }

    /** 标记被保存/删除的旧定义，等待现有玩家离场后再回收。 */
    private void reconcileArenaDefinition(ArenaSession session) {
        ArenaDefinition current = arenas.find(session.arena.name()).orElse(null);
        if (current == null || !session.matches(current)) {
            synchronized (sessionLock) {
                // 和 join/空会话回收串行化，避免定义替换恰好发生在加入事务中。
                session.retire();
                // 等待/倒计时阶段没有必要继续占用玩家；运行中的旧地图则允许
                // 当前玩家完成，避免把新坐标混入已经开始的比赛。
                MatchPhase phase = session.runtime.phase();
                if (phase == MatchPhase.WAITING || phase == MatchPhase.COUNTDOWN) {
                    session.runtime.cancel();
                }
            }
        }
    }

    private void cleanupSessionIfIdle(ArenaSession session) {
        if (session == null || !session.runtime.isEmpty()) {
            return;
        }
        String key = normalizeArenaName(session.arena.name());
        synchronized (sessionLock) {
            if (sessions.get(key) != session || !session.runtime.isEmpty()) {
                return;
            }
            removeSessionLocked(key, session);
        }
    }

    private void removeSessionLocked(String key, ArenaSession session) {
        if (!sessions.remove(key, session)) {
            return;
        }
        session.retire();
        TaskHandle endTask = session.endTask();
        if (endTask != null) {
            endTask.cancel();
        }
    }

    private boolean isCurrentPlayer(UUID playerId, PlayerSession playerSession, ArenaSession session) {
        return !shuttingDown.get() && playerSession != null && session != null
                && players.get(playerId) == playerSession
                && playerSession.arenaSession() == session
                && !playerSession.cleared()
                && session.runtime.contains(playerId);
    }

    private void startRunning(ArenaSession session) {
        if (session == null || shuttingDown.get()
                || session.runtime.phase() != MatchPhase.RUNNING) {
            return;
        }
        // 配置替换可能与全局状态 tick 并发发生。若旧会话尚未真正启动就被退休，
        // 直接结束它，避免进入 RUNNING 却永远没有起点传送任务。
        if (session.retired()) {
            if (!session.started() && session.runtime.phase() != MatchPhase.FINISHED) {
                session.runtime.cancel();
            }
            return;
        }
        if (!session.markStarted()) {
            return;
        }
        session.runtime.view(System.currentTimeMillis()).players().forEach(uuid -> {
            // 传送是异步的，必须固定进入本局时的会话，避免退出后重连的新会话被旧回调修改。
            PlayerSession expectedSession = players.get(uuid);
            if (expectedSession == null || expectedSession.arenaSession() != session) {
                return;
            }
            TaskHandle startTeleportWatchdog = scheduleGlobalDelayed(() -> {
                if (!isCurrentPlayer(uuid, expectedSession, session)
                        || expectedSession.startTeleportCompleted()) {
                    return;
                }
                expectedSession.invalidateTeleport();
                if (clearAndRemember(uuid, expectedSession, session)) {
                    scheduleAfterUuidCleanup(uuid, "teleport-failed");
                }
            }, START_TELEPORT_TIMEOUT_TICKS);
            // 调度器拒绝看门狗时不能留下一个永远等待起点传送的玩家；
            // 立即按 UUID 清理会话，避免 RUNNING 状态永久占位。
            if (startTeleportWatchdog == null || startTeleportWatchdog.cancelled()) {
                expectedSession.invalidateTeleport();
                if (clearAndRemember(uuid, expectedSession, session)) {
                    scheduleAfterUuidCleanup(uuid, "teleport-failed");
                }
                return;
            }
            expectedSession.startTeleportWatchdog(startTeleportWatchdog);
            scheduler.runPlayer(uuid, player -> {
                if (!isCurrentPlayer(uuid, expectedSession, session) || expectedSession.finished()) {
                    return;
                }
                if (expectedSession.waitingTeleportPending()) {
                    // 等待区传送尚未完成时不能同时发起起点传送；否则两个
                    // teleportAsync 可能以任意顺序完成，把玩家拉回错误位置。
                    return;
                }
                beginStartTeleport(player, expectedSession, session);
            });
        });
    }

    /**
     * Starts the single allowed transition from the waiting area to the start
     * platform.  It is called only from the player's entity context.  The
     * atomic flag makes a late waiting-teleport callback and the global start
     * loop harmless when they race on the same tick.
     */
    private void beginStartTeleport(Player player, PlayerSession playerSession, ArenaSession session) {
        if (player == null || playerSession == null || session == null
                || !isCurrentPlayer(player.getUniqueId(), playerSession, session)
                || playerSession.finished()
                || session.runtime.view(System.currentTimeMillis()).phase() != MatchPhase.RUNNING
                || session.ended()
                || !playerSession.beginStartTeleport()) {
            return;
        }
        lobbyItems.removeAll(player);
        UUID playerId = player.getUniqueId();
        long teleportToken = playerSession.beginTeleport();
        teleport(player, session.arena.startSpawn(), (target, success) -> {
            if (!playerSession.isTeleportCurrent(teleportToken)) {
                return;
            }
            playerSession.cancelStartTimeout();
            boolean valid = target != null && target == player
                    && isCurrentPlayer(playerId, playerSession, session)
                    && session.runtime.view(System.currentTimeMillis()).phase() == MatchPhase.RUNNING
                    && !session.ended() && !playerSession.finished();
            if (!valid) {
                // 即使核心错误地以 success=true 返回 null/旧实例，也不能因为
                // 已取消看门狗而留下 RUNNING 幽灵玩家；按 UUID 清理并保留原点，
                // 让下次登录仍可恢复。
                clearAndRemember(playerId, playerSession, session);
                return;
            }
            if (success) {
                playerSession.startTeleportCompleted(true);
                messages.send(target, "game-started", Map.of());
                target.sendTitlePart(net.kyori.adventure.title.TitlePart.TITLE,
                        messages.renderRaw("game-title", Map.of()));
                target.sendTitlePart(net.kyori.adventure.title.TitlePart.SUBTITLE,
                        messages.renderRaw("game-subtitle", Map.of()));
                long startedAt = session.runtime.view(System.currentTimeMillis()).startedAtMillis();
                playerSession.startedAtMillis(startedAt > 0 ? startedAt : System.currentTimeMillis());
            } else if (removePlayer(target, playerSession, true, session, false)) {
                messages.send(target, "teleport-failed", Map.of());
            }
        });
    }

    private void finish(Player player, PlayerSession playerSession, ArenaSession session) {
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session) || playerSession.finished()) {
            return;
        }
        long now = System.currentTimeMillis();
        MatchView beforeFinish = session.runtime.view(now);
        OptionalInt rank = session.runtime.finish(player.getUniqueId(), now);
        if (rank.isEmpty()) {
            return;
        }
        if (!playerSession.markFinished()) {
            return;
        }
        // 离场/掉线可能与到达终点并发；若会话已被清理，不再向新连接
        // 发送旧比赛结果或写入旧玩家成绩。
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)) {
            return;
        }
        long startedAt = playerSession.startedAtMillis() > 0
                ? playerSession.startedAtMillis() : beforeFinish.startedAtMillis();
        long elapsed = Math.max(0, now - startedAt);
        messages.send(player, "game-finished", Map.of("rank", rank.getAsInt()));
        String identity = identity(player, session);
        String name = player.getName();
        UUID playerId = player.getUniqueId();
        scheduler.runAsync(() -> {
            try {
                boolean improved = records.saveIfBetter(session.arena.name(), identity, name, elapsed,
                        session.identityMode, session.saveLastKnownName);
                if (improved) {
                    scheduler.runPlayer(playerId, target -> {
                        if (players.get(playerId) == playerSession) {
                            messages.send(target, "new-best", Map.of());
                        }
                    });
                }
            } catch (Exception exception) {
                pluginLog("无法保存最佳成绩：" + exception.getMessage(), exception);
            }
        });
        // 只给第一名播放庆祝效果，避免多人同时到达时产生大量粒子。
        if (session.finishFirework && rank.getAsInt() == 1) {
            // 明确回到玩家实体所有者上下文，避免在 Folia 区域之间直接操作 World。
            scheduler.runEntity(player, () -> {
                // 视觉任务可能排在玩家主动离场/重连之后；只给仍属于
                // 原比赛会话的实体播放，避免旧回调在新位置留下效果。
                if (!player.isOnline() || !isCurrentPlayer(playerId, playerSession, session)
                        || !playerSession.finished()) {
                    return;
                }
                try {
                    player.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, player.getLocation(),
                            24, 0.4, 0.6, 0.4, 0.02);
                } catch (RuntimeException exception) {
                    pluginLog("播放胜者庆祝效果失败：" + exception.getMessage(), exception);
                }
            });
        }
    }

    private void recover(Player player, PlayerSession playerSession, ArenaSession session) {
        recover(player, playerSession, session, false);
    }

    /**
     * 普通越界恢复受冷却限制；死亡恢复不能被冷却吞掉，否则玩家重生后可能仍在
     * 虚空中并再次死亡。allowFinished 仅用于结果展示/已完成选手的死亡兜底。
     */
    private void recover(Player player, PlayerSession playerSession, ArenaSession session,
                         boolean allowFinished) {
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)
                || (!allowFinished && playerSession.finished())) {
            return;
        }
        long now = System.currentTimeMillis();
        MatchView view = session.runtime.view(now);
        if (view.phase() == MatchPhase.FINISHED && !allowFinished) {
            return;
        }
        if (allowFinished) {
            // 死亡恢复必须覆盖仍在途中的普通越界恢复，不应被
            // recoveryPending 标志吞掉。
            if (!playerSession.forceRecovery(now)) {
                return;
            }
        } else if (!playerSession.beginRecovery(now, session.recoveryCooldownMillis)) {
            return;
        }
        long teleportToken = playerSession.beginTeleport();
        int completed = Math.max(0, Math.min(playerSession.completedFloors(), session.arena.floorGates().size()));
        ArenaPoint safe = allowFinished && playerSession.finished()
                ? session.arena.finishPoint()
                : view.phase() == MatchPhase.RUNNING && completed > 0
                ? session.arena.floorGates().get(completed - 1) :
                (view.phase() == MatchPhase.RUNNING ? session.arena.startSpawn() : session.arena.waitingSpawn());
        TaskHandle recoveryTimeout = scheduleGlobalDelayed(() -> {
            if (!playerSession.isTeleportCurrent(teleportToken)
                    || !isCurrentPlayer(playerSession.playerId(), playerSession, session)) {
                return;
            }
            playerSession.invalidateTeleport();
            playerSession.endRecovery();
            if (clearAndRemember(playerSession.playerId(), playerSession, session)) {
                scheduleAfterUuidCleanup(playerSession.playerId(), "teleport-failed");
            }
        }, TELEPORT_WATCHDOG_TICKS);
        if (!usable(recoveryTimeout)) {
            playerSession.endRecovery();
            if (clearAndRemember(playerSession.playerId(), playerSession, session)) {
                scheduleAfterUuidCleanup(playerSession.playerId(), "teleport-failed");
            }
            return;
        }
        playerSession.recoveryTimeout(recoveryTimeout);
        teleport(player, safe, (target, success) -> {
            if (!playerSession.isTeleportCurrent(teleportToken)) {
                return;
            }
            playerSession.cancelRecoveryTimeout();
            playerSession.endRecovery();
            if (!isCurrentPlayer(playerSession.playerId(), playerSession, session)) {
                return;
            }
            if (success && target != null && target == player) {
                restoreAfterRecovery(target);
                messages.send(target, "player-recovered", Map.of("floor", completed));
            } else {
                if (target != null && target == player) {
                    if (clearAndRemember(target, playerSession, session)) {
                        messages.send(target, "teleport-failed", Map.of());
                    }
                } else if (clearAndRemember(playerSession.playerId(), playerSession, session)) {
                    scheduleAfterUuidCleanup(playerSession.playerId(), "teleport-failed");
                }
            }
        });
    }

    private void schedulePostDeathRecovery(Player player, PlayerSession playerSession,
                                            ArenaSession session, int attempt) {
        if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)
                || (attempt == 0 && !playerSession.beginDeathRecovery())) {
            return;
        }
        TaskHandle recoveryStep = null;
        try {
            recoveryStep = scheduler.runEntityDelayed(player, () -> {
            if (!isCurrentPlayer(player.getUniqueId(), playerSession, session)) {
                playerSession.endDeathRecovery();
                return;
            }
            if (player.isDead()) {
                try {
                    // 仅在玩家实体上下文请求立即重生，避免依赖 Folia 不可用的重生事件。
                    player.spigot().respawn();
                } catch (RuntimeException exception) {
                    pluginLog("请求玩家重生失败：" + exception.getMessage(), exception);
                }
                if (attempt < 200) {
                    schedulePostDeathRecovery(player, playerSession, session, attempt + 1);
                } else {
                    playerSession.endDeathRecovery();
                    rememberRecovery(playerSession, "无法保存死亡恢复记录：");
                    // 长时间停留在死亡界面时释放比赛状态，避免永久占用竞技场槽位。
                    clearPlayerState(player, playerSession, session);
                }
                return;
            }
            playerSession.endDeathRecovery();
            // 死亡恢复必须绕过普通恢复冷却；已完成选手或结果展示阶段改送到
            // 终点/等待点，避免死亡界面和虚空状态阻塞结束流程。
            // 让仍在途中的普通恢复失效，再启动强制恢复，避免
            // recoveryPending 把本次死亡恢复直接跳过。
            playerSession.invalidateTeleport();
            playerSession.endRecovery();
            recover(player, playerSession, session, true);
            }, 1);
        } catch (RuntimeException exception) {
            pluginLog("死亡恢复任务调度失败：" + exception.getMessage(), exception);
        }
        if (!usable(recoveryStep)) {
            playerSession.endDeathRecovery();
            if (clearAndRemember(player.getUniqueId(), playerSession, session)) {
                scheduleAfterUuidCleanup(player.getUniqueId(), "teleport-failed");
            }
        }
    }

    private boolean removePlayer(Player player, PlayerSession playerSession, boolean restore) {
        return removePlayer(player, playerSession, restore, playerSession.arenaSession());
    }

    private boolean removePlayer(Player player, PlayerSession playerSession, boolean restore, ArenaSession arenaSession) {
        return removePlayer(player, playerSession, restore, arenaSession, true);
    }

    private boolean removePlayer(Player player, PlayerSession playerSession, boolean restore,
                                 ArenaSession arenaSession, boolean preserveOnProxyFailure) {
        if (player == null || playerSession == null || !playerSession.beginRemoval()) {
            return false;
        }
        boolean online;
        try {
            online = player.isOnline();
        } catch (RuntimeException exception) {
            online = false;
        }
        if (restore && online && proxy.enabled()) {
            boolean submitted = proxy.connectToLobby(player);
            if (submitted) {
                if (clearPlayerState(player, playerSession, arenaSession)) {
                    messages.send(player, "lobby-switching", Map.of());
                }
                return true;
            }
            messages.send(player, "proxy-switch-failed", Map.of());
            if (preserveOnProxyFailure) {
                playerSession.cancelRemoval();
                return false;
            }
        }
        // 先写入不可变恢复快照，再清理内存会话；服务器在后续传送失败时仍可恢复。
        if (restore) {
            // 即使玩家恰好在本地恢复前离线，也必须保留不可变原点；否则
            // 退出事件与离场回调的竞态会让下一次登录失去恢复位置。
            recoveries.remember(playerSession.playerId(), playerSession.originalPoint());
            saveRecoveriesAsync("无法保存离场恢复记录：");
        }
        // 只有真正取得清理权的调用方才能继续发起本地恢复传送。
        // 退出事件、结束看门狗和玩家主动离场可能并发到达；如果这里
        // 已经被另一个路径清理，继续传送会把旧会话坐标写回新连接。
        if (!clearPlayerState(player, playerSession, arenaSession)) {
            return false;
        }
        if (restore && online) {
            UUID playerId = player.getUniqueId();
            PlayerSession previousRestore = pendingRestores.put(playerId, playerSession);
            if (previousRestore != null && previousRestore != playerSession) {
                // 旧恢复传送的回调即使稍后到达也会被身份检查拒绝；取消其超时句柄
                // 可避免同一 UUID 长期保留多个恢复任务。
                previousRestore.cancelRestoreTimeout();
            }
            long teleportToken = playerSession.beginTeleport();
            TaskHandle restoreTimeout = scheduleGlobalDelayed(() -> {
                if (!playerSession.isTeleportCurrent(teleportToken)) {
                    return;
                }
                if (pendingRestores.remove(playerId, playerSession)) {
                    playerSession.invalidateTeleport();
                    // Only notify/clean the original entity instance.  A reconnect
                    // with the same UUID must never receive an old failure.
                    scheduleAfterUuidCleanup(playerId, "teleport-failed");
                }
            }, TELEPORT_WATCHDOG_TICKS);
            if (!usable(restoreTimeout)) {
                pendingRestores.remove(playerId, playerSession);
                playerSession.invalidateTeleport();
                messages.send(player, "teleport-failed", Map.of());
                return true;
            }
            playerSession.restoreTimeout(restoreTimeout);
            teleport(player, playerSession.originalPoint(), (target, success) -> {
                if (!playerSession.isTeleportCurrent(teleportToken)) {
                    return;
                }
                playerSession.cancelRestoreTimeout();
                if (target == null) {
                    // 调度器在玩家离线/区域退休时以 null 报告失败；只做
                    // UUID 级闸门清理，绝不访问失效 Player。
                    pendingRestores.remove(playerId, playerSession);
                    return;
                }
                // 只允许原 Player 实例完成恢复，重连后的新实例不得被旧传送回调改写状态。
                // 先无条件摘除本次 UUID 闸门；不能用短路表达式把 remove 放在
                // target 身份判断之后，否则错误实例回调会留下永不过期的 pending 状态。
                boolean removed = pendingRestores.remove(playerId, playerSession);
                if (target != player || shuttingDown.get() || !removed || players.containsKey(playerId)) {
                    return;
                }
                if (success) {
                    target.setGameMode(playerSession.originalGameMode());
                    target.setHealth(Math.min(maxHealth(target), playerSession.originalHealth()));
                    target.setFoodLevel(playerSession.originalFoodLevel());
                    messages.send(target, "left", Map.of());
                    recoveries.forget(target.getUniqueId());
                    scheduler.runAsync(() -> saveRecoveries("无法清理离场恢复记录："));
                } else {
                    messages.send(target, "teleport-failed", Map.of());
                }
            });
        }
        return true;
    }

    private boolean clearPlayerState(Player player, PlayerSession playerSession, ArenaSession arenaSession) {
        if (playerSession == null || !playerSession.markCleared()) {
            return false;
        }
        UUID playerId = player == null ? playerSession.playerId() : player.getUniqueId();
        playerSession.invalidateTeleport();
        playerSession.cancelAllTimeouts();
        playerSession.endRecovery();
        playerSession.endDeathRecovery();
        // 清理旧会话时同时撤销可能残留的本地恢复闸门；否则同 UUID
        // 的下一次登录/加入会被误判为仍在恢复中。
        pendingRestores.remove(playerId, playerSession);
        if (arenaSession != null) {
            arenaSession.runtime.leave(playerId);
        }
        players.remove(playerId, playerSession);
        if (player != null) {
            try {
                if (player.isOnline()) {
                    // 道具只在小游戏会话内有效，离场前统一清理以防复制或跨局残留。
                    lobbyItems.removeAll(player);
                }
            } catch (RuntimeException exception) {
                // 清理状态不能因失效/退休的实体实例中断；UUID 级状态已经
                // 删除，玩家下次登录仍可通过恢复记录回到原位置。
                pluginLog("清理玩家返回大厅道具失败：" + exception.getMessage(), exception);
            }
        }
        cleanupSessionIfIdle(arenaSession);
        return true;
    }

    /**
     * UUID-only variant used by global watchdogs. It deliberately does not touch
     * Player, World or Inventory because the current thread may not own that entity.
     */
    private boolean clearPlayerStateById(UUID playerId, PlayerSession playerSession, ArenaSession arenaSession) {
        if (playerId == null || playerSession == null || !playerId.equals(playerSession.playerId())
                || !playerSession.markCleared()) {
            return false;
        }
        playerSession.invalidateTeleport();
        playerSession.cancelAllTimeouts();
        playerSession.endRecovery();
        playerSession.endDeathRecovery();
        pendingRestores.remove(playerId, playerSession);
        if (arenaSession != null) {
            arenaSession.runtime.leave(playerId);
        }
        players.remove(playerId, playerSession);
        cleanupSessionIfIdle(arenaSession);
        return true;
    }

    /**
     * 结束状态先展示结果，再在全局上下文延迟清理并切换子服。
     * 这里只跨线程传递 UUID 和不可变比赛快照，玩家实体操作留在实体调度器中。
     */
    private void scheduleEndSession(ArenaSession session, FinishReason reason) {
        if (session == null || !session.markEnded()) {
            return;
        }
        String message = switch (reason) {
            case TIMEOUT -> "game-timeout";
            case CANCELLED -> "game-cancelled";
            default -> "game-over";
        };
        MatchView view = session.runtime.view(System.currentTimeMillis());
        for (UUID uuid : new ArrayList<>(view.players())) {
            PlayerSession playerSession = players.get(uuid);
            if (playerSession == null) {
                continue;
            }
            scheduler.runPlayer(uuid, player -> {
                // 玩家可能在展示阶段主动离场；不向新会话或已清理玩家重复发送结束消息。
                if (players.get(uuid) == playerSession) {
                    messages.send(player, message, Map.of());
                }
            });
        }
        long delayTicks = resultDisplayDelayTicks(session.resultDisplaySeconds);
        TaskHandle endTask = scheduler.runGlobalDelayed(() -> {
            session.endTask(null);
            endSession(session);
        }, delayTicks);
        if (endTask == null || endTask.cancelled()) {
            // 调度器拒绝结果计时任务时不能把会话永久留在 FINISHED 状态；
            // 立即走同一条清理路径（关闭阶段则由 shutdown 负责最终清理）。
            session.endTask(null);
            if (!shuttingDown.get()) {
                endSession(session);
            }
        } else {
            session.endTask(endTask);
        }
    }

    /** 在结果展示计时结束后执行实际离场和代理切服。 */
    private void endSession(ArenaSession session) {
        List<UUID> playerIds = new ArrayList<>(session.runtime.view(System.currentTimeMillis()).players());
        for (UUID uuid : playerIds) {
            PlayerSession playerSession = players.get(uuid);
            if (playerSession != null) {
                scheduler.runPlayer(uuid, player -> {
                    // 玩家可能在实体任务执行前主动离场；旧任务不得清理新会话或重复发送结束消息。
                    if (players.get(uuid) != playerSession) {
                        return;
                    }
                    // 比赛已经结束，代理失败也必须释放本局状态，避免悬挂会话阻塞后续比赛。
                    removePlayer(player, playerSession, true, session, false);
                });
            }
        }
        if (!playerIds.isEmpty()) {
            // 不能只依赖 runPlayer：玩家断线、实体 Region 退休或调度器拒绝任务时，
            // 回调可能永远不会到达。看门狗只操作 UUID、会话和不可变恢复坐标，
            // 不跨 Region 读取 Player/Inventory。
            TaskHandle cleanupWatchdog = scheduler.runGlobalDelayed(
                    () -> forceCleanupEndedPlayers(session, playerIds),
                    END_SESSION_CLEANUP_TIMEOUT_TICKS);
            if (cleanupWatchdog == null || cleanupWatchdog.cancelled()) {
                forceCleanupEndedPlayers(session, playerIds);
            }
        }
        // 保留已结束但仍有玩家的会话占位。若此处立即移除，新的 /hfr join
        // 会在旧玩家真正切服/恢复前创建同名会话，导致两局同时写入同一张地图，
        // 也会让旧传送回调和新会话互相干扰。最后一个旧玩家被清理后，
        // clearPlayerState/clearPlayerStateById 会通过 cleanupSessionIfIdle 回收。
        cleanupSessionIfIdle(session);
    }

    /** 全局上下文中的结束会话兜底；旧会话身份检查防止误清理同 UUID 的新会话。 */
    private void forceCleanupEndedPlayers(ArenaSession session, List<UUID> playerIds) {
        if (session == null || playerIds == null || playerIds.isEmpty()) {
            return;
        }
        boolean remembered = false;
        for (UUID playerId : playerIds) {
            PlayerSession playerSession = players.get(playerId);
            if (playerSession == null) {
                // runtime 是独立状态机；即使 PlayerSession 因异常路径已经丢失，
                // 也必须移除这个 UUID，否则 FINISHED 会话永远不会变空。
                session.runtime.leave(playerId);
                continue;
            }
            if (playerSession.arenaSession() != session) {
                continue;
            }
            if (clearPlayerStateById(playerId, playerSession, session)) {
                recoveries.remember(playerId, playerSession.originalPoint());
                // UUID 清理后再回到实体上下文，处理 runPlayer 首次调度丢失时
                // 无法删除的返回大厅道具；若玩家已重连并建立新状态则跳过。
                scheduleAfterUuidCleanup(playerId, null);
                remembered = true;
            }
        }
        if (remembered) {
            saveRecoveriesAsync("无法保存结束会话恢复记录：");
        }
    }

    private void saveRecoveriesAsync(String messagePrefix) {
        try {
            scheduler.runAsync(() -> saveRecoveries(messagePrefix));
        } catch (RuntimeException exception) {
            // 运行时调度器拒绝异步保存时保留内存记录；插件关闭阶段还会做
            // 最终同步落盘，不能让异常冒泡中断比赛状态清理。
            pluginLog("恢复记录异步保存任务调度失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 调度全局看门狗的统一入口。第三方调度器拒绝任务时返回 null，调用方
     * 必须立即结束对应的 pending 状态，不能把“没有看门狗”当成成功登记。
     */
    private TaskHandle scheduleGlobalDelayed(Runnable task, long delayTicks) {
        if (task == null || shuttingDown.get()) {
            return null;
        }
        try {
            return scheduler.runGlobalDelayed(task, Math.max(1L, delayTicks));
        } catch (RuntimeException exception) {
            pluginLog("全局看门狗调度失败：" + exception.getMessage(), exception);
            return null;
        }
    }

    private static boolean usable(TaskHandle handle) {
        return handle != null && !handle.cancelled();
    }

    /**
     * UUID 兜底清理完成后回到玩家实体上下文，清除可能残留的返回大厅道具。
     * 全局线程只传递 UUID；若同 UUID 已建立新会话，则不触碰新玩家的背包。
     */
    private void scheduleAfterUuidCleanup(UUID playerId, String messageKey) {
        if (playerId == null) {
            return;
        }
        try {
            scheduler.runPlayer(playerId, target -> {
                if (players.containsKey(playerId) || pendingRestores.containsKey(playerId)
                        || loginRestores.containsKey(playerId)) {
                    return;
                }
                try {
                    lobbyItems.removeAll(target);
                } catch (RuntimeException exception) {
                    pluginLog("清理兜底返回大厅道具失败：" + exception.getMessage(), exception);
                }
                if (messageKey != null && !messageKey.isBlank()) {
                    messages.send(target, messageKey, Map.of());
                }
            });
        } catch (RuntimeException exception) {
            pluginLog("玩家兜底清理任务调度失败：" + exception.getMessage(), exception);
        }
    }

    /** 传送恢复超时仍保留会话时，只给原会话玩家发送一次失败提示。 */
    private void scheduleRecoveryFailureMessage(UUID playerId, PlayerSession expected, String messageKey) {
        if (playerId == null || expected == null || messageKey == null || messageKey.isBlank()) {
            return;
        }
        try {
            scheduler.runPlayer(playerId, target -> {
                if (players.get(playerId) == expected && !expected.cleared()) {
                    messages.send(target, messageKey, Map.of());
                }
            });
        } catch (RuntimeException exception) {
            pluginLog("恢复失败提示调度失败：" + exception.getMessage(), exception);
        }
    }

    static long resultDisplayDelayTicks(int seconds) {
        return Math.max(1L, seconds * 20L);
    }

    private void teleport(Player expectedPlayer, ArenaPoint point,
                          java.util.function.BiConsumer<Player, Boolean> completion) {
        if (completion == null) {
            return;
        }
        if (expectedPlayer == null || point == null || shuttingDown.get()) {
            invokeTeleportFailure(completion);
            return;
        }
        try {
            scheduler.teleport(expectedPlayer, point.world(), point.x(), point.y(), point.z(),
                    point.yaw(), point.pitch(), completion);
        } catch (RuntimeException exception) {
            pluginLog("提交异步传送失败：" + exception.getMessage(), exception);
            // TaskScheduler 的实现通常会自行回调失败；这里覆盖第三方实现
            // 同步抛异常的路径，避免 recoveryPending/loginRestores 永久卡住。
            invokeTeleportFailure(completion);
        }
    }

    private void invokeTeleportFailure(java.util.function.BiConsumer<Player, Boolean> completion) {
        try {
            completion.accept(null, false);
        } catch (RuntimeException exception) {
            pluginLog("传送失败回调执行异常：" + exception.getMessage(), exception);
        }
    }

    private static String normalizeArenaName(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static ArenaPoint snapshot(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("玩家当前位置没有有效世界");
        }
        return new ArenaPoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static double safeHealth(Player player) {
        double maximum = maxHealth(player);
        double health = player.getHealth();
        if (!Double.isFinite(health) || health <= 0.0) {
            return maximum;
        }
        return Math.min(maximum, health);
    }

    private static int safeFoodLevel(Player player) {
        return Math.max(0, Math.min(20, player.getFoodLevel()));
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null || !Double.isFinite(attribute.getValue()) || attribute.getValue() <= 0.0) {
            return 20.0;
        }
        return attribute.getValue();
    }

    /**
     * 伤害事件已取消后仍要把玩家从临界死亡状态拉回有效生命值；部分核心会在
     * 同一 tick 继续处理火焰/摔落状态，清理这些状态可以避免重复触发死亡事件。
     * 调用方必须位于玩家实体所有者上下文。
     */
    private void stabilizeAfterDamage(Player player) {
        try {
            double current = player.getHealth();
            double maximum = maxHealth(player);
            double safe = Double.isFinite(current) ? Math.max(1.0, Math.min(maximum, current)) : 1.0;
            player.setHealth(safe);
            player.setFireTicks(0);
            player.setFallDistance(0.0F);
        } catch (RuntimeException exception) {
            pluginLog("无法重置玩家死亡状态：" + exception.getMessage(), exception);
        }
    }

    /** 恢复传送成功后补满生命并清理会导致再次死亡的状态。 */
    private void restoreAfterRecovery(Player player) {
        try {
            player.setHealth(maxHealth(player));
            player.setFireTicks(0);
            player.setFallDistance(0.0F);
        } catch (RuntimeException exception) {
            pluginLog("无法完成玩家恢复状态重置：" + exception.getMessage(), exception);
        }
    }

    private void pluginLog(String message, Throwable throwable) {
        Bukkit.getLogger().log(java.util.logging.Level.WARNING, message, throwable);
    }

    private void saveRecoveries(String messagePrefix) {
        try {
            recoveries.save();
        } catch (Exception exception) {
            pluginLog(messagePrefix + exception.getMessage(), exception);
        }
    }

    private void completeLoginRestore(UUID playerId, LoginRestore restore) {
        if (playerId == null || restore == null) {
            return;
        }
        if (loginRestores.remove(playerId, restore)) {
            restore.invalidateTeleport();
            restore.cancelTimeout();
        }
    }

    /**
     * 将登录恢复闸门与比赛玩家登记串行化。replace=true 仅用于同一连接收到
     * 重复 Join 事件时替换旧恢复；普通延迟检查使用 put-if-absent，避免覆盖
     * 已在执行的恢复请求。
     */
    private LoginRestoreRegistration registerLoginRestore(UUID playerId, LoginRestore restore,
                                                           boolean replace) {
        if (playerId == null || restore == null) {
            return new LoginRestoreRegistration(false, null);
        }
        synchronized (sessionLock) {
            if (shuttingDown.get() || players.containsKey(playerId)
                    || pendingRestores.containsKey(playerId)) {
                return new LoginRestoreRegistration(false, null);
            }
            if (replace) {
                return new LoginRestoreRegistration(true, loginRestores.put(playerId, restore));
            }
            LoginRestore previous = loginRestores.putIfAbsent(playerId, restore);
            return new LoginRestoreRegistration(previous == null, previous);
        }
    }

    /** 清理失效比赛并保存不可变原点，供玩家下次登录恢复。 */
    private boolean clearAndRemember(UUID playerId, PlayerSession playerSession, ArenaSession arenaSession) {
        if (!clearPlayerStateById(playerId, playerSession, arenaSession)) {
            return false;
        }
        rememberRecovery(playerSession, "无法保存传送失败恢复记录：");
        return true;
    }

    /** 实体上下文版本会同时清理返回大厅道具。 */
    private boolean clearAndRemember(Player player, PlayerSession playerSession, ArenaSession arenaSession) {
        if (!clearPlayerState(player, playerSession, arenaSession)) {
            return false;
        }
        rememberRecovery(playerSession, "无法保存传送失败恢复记录：");
        return true;
    }

    private void rememberRecovery(PlayerSession playerSession, String messagePrefix) {
        if (playerSession == null) {
            return;
        }
        recoveries.remember(playerSession.playerId(), playerSession.originalPoint());
        saveRecoveriesAsync(messagePrefix);
    }

    private static final class ArenaSession {
        private final ArenaDefinition arena;
        private final ArenaRuntime runtime;
        private final int countdownSeconds;
        private final int resultDisplaySeconds;
        private final double gateRadius;
        private final double finishRadius;
        private final long recoveryCooldownMillis;
        private final boolean finishFirework;
        private final IdentityMode identityMode;
        private final boolean saveLastKnownName;
        private final boolean offlineNameIgnoreCase;
        private volatile boolean ended;
        private volatile boolean retired;
        private volatile boolean started;
        private volatile TaskHandle endTask;

        private ArenaSession(ArenaDefinition arena, ArenaRuntime runtime, int countdownSeconds,
                             int resultDisplaySeconds,
                             double gateRadius, double finishRadius, int recoveryCooldownSeconds,
                             boolean finishFirework, IdentityMode identityMode,
                             boolean saveLastKnownName, boolean offlineNameIgnoreCase) {
            this.arena = arena;
            this.runtime = runtime;
            this.countdownSeconds = Math.max(1, countdownSeconds);
            this.resultDisplaySeconds = resultDisplaySeconds;
            this.gateRadius = gateRadius;
            this.finishRadius = finishRadius;
            this.recoveryCooldownMillis = Math.max(0L, recoveryCooldownSeconds) * 1_000L;
            this.finishFirework = finishFirework;
            this.identityMode = identityMode;
            this.saveLastKnownName = saveLastKnownName;
            this.offlineNameIgnoreCase = offlineNameIgnoreCase;
        }

        private boolean matches(ArenaDefinition definition) {
            return definition != null && arena.equals(definition);
        }

        private boolean ended() {
            return ended;
        }

        private boolean retired() {
            return retired;
        }

        private boolean started() {
            return started;
        }

        private void retire() {
            retired = true;
        }

        private synchronized boolean markStarted() {
            if (started || ended || retired) {
                return false;
            }
            started = true;
            return true;
        }

        private synchronized boolean markEnded() {
            if (ended) {
                return false;
            }
            ended = true;
            return true;
        }

        private TaskHandle endTask() {
            return endTask;
        }

        private void endTask(TaskHandle task) {
            endTask = task;
        }
    }

    private static final class PlayerSession {
        private final Player expectedPlayer;
        private final UUID playerId;
        private final String arenaName;
        private final ArenaSession arenaSession;
        private final ArenaPoint originalPoint;
        private final GameMode originalGameMode;
        private final double originalHealth;
        private final int originalFoodLevel;
        private volatile int completedFloors;
        private volatile long startedAtMillis;
        private volatile long lastRecoveryMillis = -1L;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean cleared = new AtomicBoolean();
        private final AtomicBoolean removalInProgress = new AtomicBoolean();
        private final AtomicBoolean recoveryPending = new AtomicBoolean();
        private final AtomicBoolean deathRecoveryPending = new AtomicBoolean();
        private final AtomicBoolean waitingTeleportPending = new AtomicBoolean();
        private final AtomicBoolean startTeleportPending = new AtomicBoolean();
        private final AtomicBoolean startTeleportCompleted = new AtomicBoolean();
        private final AtomicLong teleportGeneration = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicReference<TaskHandle> joinTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<TaskHandle> restoreTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<TaskHandle> recoveryTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<TaskHandle> startTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();

        private PlayerSession(Player expectedPlayer, UUID playerId, String arenaName, ArenaSession arenaSession,
                              ArenaPoint originalPoint, GameMode originalGameMode,
                              double originalHealth, int originalFoodLevel) {
            this.expectedPlayer = expectedPlayer;
            this.playerId = playerId;
            this.arenaName = arenaName;
            this.arenaSession = arenaSession;
            this.originalPoint = originalPoint;
            this.originalGameMode = originalGameMode;
            this.originalHealth = originalHealth;
            this.originalFoodLevel = originalFoodLevel;
        }

        private String arenaName() { return arenaName; }
        private UUID playerId() { return playerId; }
        private boolean isExpectedPlayer(Player player) { return player != null && player == expectedPlayer; }
        private ArenaSession arenaSession() { return arenaSession; }
        private ArenaPoint originalPoint() { return originalPoint; }
        private GameMode originalGameMode() { return originalGameMode; }
        private double originalHealth() { return originalHealth; }
        private int originalFoodLevel() { return originalFoodLevel; }
        private int completedFloors() { return completedFloors; }
        private void completedFloors(int value) { completedFloors = value; }
        private long startedAtMillis() { return startedAtMillis; }
        private void startedAtMillis(long value) { startedAtMillis = value; }
        private long lastRecoveryMillis() { return lastRecoveryMillis; }
        private void lastRecoveryMillis(long value) { lastRecoveryMillis = value; }
        private boolean markFinished() { return finished.compareAndSet(false, true); }
        private boolean finished() { return finished.get(); }
        private boolean markCleared() { return cleared.compareAndSet(false, true); }
        private boolean cleared() { return cleared.get(); }
        private boolean beginRemoval() {
            return !cleared.get() && removalInProgress.compareAndSet(false, true);
        }
        private void cancelRemoval() { removalInProgress.set(false); }
        private boolean beginRecovery(long now, long cooldownMillis) {
            if (!recoveryPending.compareAndSet(false, true)) {
                return false;
            }
            long last = lastRecoveryMillis;
            if (cooldownMillis > 0 && last >= 0 && now - last < cooldownMillis) {
                recoveryPending.set(false);
                return false;
            }
            lastRecoveryMillis = now;
            return true;
        }
        private boolean forceRecovery(long now) {
            if (!recoveryPending.compareAndSet(false, true)) {
                return false;
            }
            lastRecoveryMillis = now;
            return true;
        }
        private void endRecovery() { recoveryPending.set(false); }
        private boolean beginDeathRecovery() { return deathRecoveryPending.compareAndSet(false, true); }
        private void endDeathRecovery() { deathRecoveryPending.set(false); }
        private boolean waitingTeleportPending() { return waitingTeleportPending.get(); }
        private void waitingTeleportPending(boolean value) { waitingTeleportPending.set(value); }
        private boolean beginStartTeleport() {
            return startTeleportPending.compareAndSet(false, true);
        }
        private boolean startTeleportCompleted() { return startTeleportCompleted.get(); }
        private void startTeleportCompleted(boolean value) { startTeleportCompleted.set(value); }
        private long beginTeleport() { return teleportGeneration.incrementAndGet(); }
        /** Token validity is separate from membership: local return-to-origin
         * teleports intentionally run after the match session is marked cleared. */
        private boolean isTeleportCurrent(long token) { return teleportGeneration.get() == token; }
        private void invalidateTeleport() { teleportGeneration.incrementAndGet(); }
        private void joinTimeout(TaskHandle task) {
            TaskHandle previous = joinTimeout.getAndSet(task);
            if (previous != null) {
                previous.cancel();
            }
        }
        private void cancelJoinTimeout() {
            TaskHandle task = joinTimeout.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
        private void restoreTimeout(TaskHandle task) {
            TaskHandle previous = restoreTimeout.getAndSet(task);
            if (previous != null) {
                previous.cancel();
            }
        }
        private void cancelRestoreTimeout() {
            TaskHandle task = restoreTimeout.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
        private void recoveryTimeout(TaskHandle task) {
            TaskHandle previous = recoveryTimeout.getAndSet(task);
            if (previous != null) {
                previous.cancel();
            }
        }
        private void cancelRecoveryTimeout() {
            TaskHandle task = recoveryTimeout.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
        private void startTeleportWatchdog(TaskHandle task) {
            TaskHandle previous = startTimeout.getAndSet(task);
            if (previous != null) {
                previous.cancel();
            }
        }
        private void cancelStartTimeout() {
            TaskHandle task = startTimeout.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
        private void cancelAllTimeouts() {
            cancelJoinTimeout();
            cancelRestoreTimeout();
            cancelRecoveryTimeout();
            cancelStartTimeout();
        }
    }

    /** Login restore marker; the expected Player reference is held only until
     * completion/timeout so a same-UUID reconnect cannot inherit an old move. */
    private static final class LoginRestore {
        private final Player expectedPlayer;
        private final AtomicLong teleportGeneration = new AtomicLong();
        private final java.util.concurrent.atomic.AtomicReference<TaskHandle> timeout =
                new java.util.concurrent.atomic.AtomicReference<>();

        private LoginRestore(Player expectedPlayer) {
            this.expectedPlayer = expectedPlayer;
        }

        private boolean isExpected(Player player) {
            return player == expectedPlayer;
        }

        private long beginTeleport() {
            return teleportGeneration.incrementAndGet();
        }

        private boolean isTeleportCurrent(long token) {
            return teleportGeneration.get() == token;
        }

        private void invalidateTeleport() {
            teleportGeneration.incrementAndGet();
        }

        private void timeout(TaskHandle task) {
            TaskHandle previous = timeout.getAndSet(task);
            if (previous != null) {
                previous.cancel();
            }
        }

        private void cancelTimeout() {
            TaskHandle task = timeout.getAndSet(null);
            if (task != null) {
                task.cancel();
            }
        }
    }

    private record LoginRestoreRegistration(boolean accepted, LoginRestore previous) {
    }
}
