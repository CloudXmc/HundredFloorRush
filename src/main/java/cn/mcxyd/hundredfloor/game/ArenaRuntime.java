package cn.mcxyd.hundredfloor.game;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

public final class ArenaRuntime {

    private final String arenaName;
    private final int minimumPlayers;
    private final int maximumPlayers;
    private final int countdownSeconds;
    private final int timeLimitSeconds;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Map<UUID, Integer> ranks = new LinkedHashMap<>();
    /** 单局内单调递增的名次游标；玩家离场后也不能让后来者复用旧名次。 */
    private int nextRank = 1;
    private MatchPhase phase = MatchPhase.WAITING;
    private FinishReason finishReason = FinishReason.NONE;
    private long countdownEndsAtMillis;
    private long startedAtMillis;

    public ArenaRuntime(String arenaName, int minimumPlayers, int maximumPlayers,
                        int countdownSeconds, int timeLimitSeconds) {
        Objects.requireNonNull(arenaName, "arenaName");
        if (minimumPlayers < 1 || maximumPlayers < minimumPlayers
                || countdownSeconds < 1 || timeLimitSeconds < 1 || arenaName.isBlank()) {
            throw new IllegalArgumentException("invalid match limits");
        }
        this.arenaName = arenaName;
        this.minimumPlayers = minimumPlayers;
        this.maximumPlayers = maximumPlayers;
        this.countdownSeconds = countdownSeconds;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public synchronized JoinResult join(UUID playerId, long nowMillis) {
        Objects.requireNonNull(playerId, "playerId");
        if (phase == MatchPhase.RUNNING || phase == MatchPhase.FINISHED) {
            return JoinResult.RUNNING;
        }
        if (players.contains(playerId)) {
            return JoinResult.ALREADY_JOINED;
        }
        if (players.size() >= maximumPlayers) {
            return JoinResult.FULL;
        }
        players.add(playerId);
        if (phase == MatchPhase.WAITING && players.size() >= minimumPlayers) {
            phase = MatchPhase.COUNTDOWN;
            countdownEndsAtMillis = plusMillis(nowMillis, countdownSeconds * 1_000L);
        }
        return JoinResult.JOINED;
    }

    public synchronized boolean leave(UUID playerId) {
        boolean removed = players.remove(playerId);
        if (!removed) {
            return false;
        }
        // 已完成玩家的名次属于本局结果快照，离场后仍保留，避免后来者获得重复名次。
        // 未完成玩家没有名次可清理；unfinishedPlayers() 只统计当前仍在场的玩家。
        if (phase == MatchPhase.COUNTDOWN && players.size() < minimumPlayers) {
            phase = MatchPhase.WAITING;
            countdownEndsAtMillis = 0;
        } else if (phase == MatchPhase.RUNNING && unfinishedPlayers() == 0) {
            finish(FinishReason.ALL_FINISHED);
        }
        return true;
    }

    public synchronized void tick(long nowMillis) {
        if (phase == MatchPhase.COUNTDOWN && nowMillis >= countdownEndsAtMillis) {
            if (players.size() >= minimumPlayers) {
                phase = MatchPhase.RUNNING;
                // 服务器卡顿可能让倒计时截止时间早于本次 tick；比赛时限应从
                // 实际切换到 RUNNING 的时刻开始，不能因为延迟而立即超时。
                startedAtMillis = Math.max(nowMillis, countdownEndsAtMillis);
            } else {
                phase = MatchPhase.WAITING;
                // 倒计时取消后清零，避免后续重新达到人数时沿用过期时间戳。
                countdownEndsAtMillis = 0;
            }
        }
        if (phase == MatchPhase.RUNNING
                && nowMillis >= plusMillis(startedAtMillis, timeLimitSeconds * 1_000L)) {
            finish(FinishReason.TIMEOUT);
        }
    }

    /** Returns the current phase without advancing the state machine. */
    public synchronized MatchPhase phase() {
        return phase;
    }

    public synchronized boolean forceStart(long nowMillis) {
        if (players.isEmpty() || phase == MatchPhase.RUNNING || phase == MatchPhase.FINISHED) {
            return false;
        }
        phase = MatchPhase.RUNNING;
        startedAtMillis = nowMillis;
        countdownEndsAtMillis = 0;
        return true;
    }

    public synchronized OptionalInt finish(UUID playerId, long nowMillis) {
        Objects.requireNonNull(playerId, "playerId");
        tick(nowMillis);
        Integer existingRank = ranks.get(playerId);
        if (existingRank != null) {
            return OptionalInt.of(existingRank);
        }
        if (phase != MatchPhase.RUNNING || !players.contains(playerId)) {
            return OptionalInt.empty();
        }
        int rank = nextRank++;
        ranks.put(playerId, rank);
        if (unfinishedPlayers() == 0) {
            finish(FinishReason.ALL_FINISHED);
        }
        return OptionalInt.of(rank);
    }

    public synchronized void cancel() {
        if (phase != MatchPhase.FINISHED) {
            finish(FinishReason.CANCELLED);
        }
    }

    /** 返回当前是否没有任何玩家；用于服务层及时回收空会话。 */
    public synchronized boolean isEmpty() {
        return players.isEmpty();
    }

    /** 返回当前是否仍包含指定玩家；调用方可据此校验异步回调归属。 */
    public synchronized boolean contains(UUID playerId) {
        return players.contains(playerId);
    }

    public synchronized MatchView view(long nowMillis) {
        tick(nowMillis);
        int countdown = phase == MatchPhase.COUNTDOWN
                ? secondsCeiling(countdownEndsAtMillis - nowMillis) : 0;
        int remaining = phase == MatchPhase.RUNNING
                ? secondsCeiling(plusMillis(startedAtMillis, timeLimitSeconds * 1_000L) - nowMillis) : 0;
        return new MatchView(arenaName, phase, finishReason, Set.copyOf(players), Map.copyOf(ranks),
                countdown, remaining, startedAtMillis);
    }

    private int unfinishedPlayers() {
        int unfinished = 0;
        for (UUID player : players) {
            if (!ranks.containsKey(player)) {
                unfinished++;
            }
        }
        return unfinished;
    }

    private void finish(FinishReason reason) {
        if (phase == MatchPhase.FINISHED) {
            return;
        }
        phase = MatchPhase.FINISHED;
        finishReason = reason;
    }

    private static int secondsCeiling(long millis) {
        if (millis <= 0) {
            return 0;
        }
        long seconds = (millis - 1L) / 1_000L + 1L;
        return seconds >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static long plusMillis(long base, long delta) {
        if (delta > 0 && base > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        if (delta < 0 && base < Long.MIN_VALUE - delta) {
            return Long.MIN_VALUE;
        }
        return base + delta;
    }
}
