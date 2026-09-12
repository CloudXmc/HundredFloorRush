package cn.mcxyd.hundredfloor.game;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record MatchView(
        String arenaName,
        MatchPhase phase,
        FinishReason finishReason,
        Set<UUID> players,
        Map<UUID, Integer> ranks,
        int countdownSeconds,
        int remainingSeconds,
        long startedAtMillis
) {
}
