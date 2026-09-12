package cn.mcxyd.hundredfloor.game;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArenaRuntimeTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void startsCountdownAtMinimumPlayersAndCancelsWhenPlayerLeaves() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 2, 8, 5, 300);

        assertEquals(JoinResult.JOINED, runtime.join(ALICE, 1_000));
        assertEquals(MatchPhase.WAITING, runtime.view(1_000).phase());

        assertEquals(JoinResult.JOINED, runtime.join(BOB, 2_000));
        assertEquals(MatchPhase.COUNTDOWN, runtime.view(2_000).phase());
        assertEquals(5, runtime.view(2_000).countdownSeconds());

        assertTrue(runtime.leave(BOB));
        assertEquals(MatchPhase.WAITING, runtime.view(2_100).phase());
    }

    @Test
    void transitionsToRunningAndTimesOut() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 8, 3, 30);
        runtime.join(ALICE, 10_000);

        assertEquals(MatchPhase.COUNTDOWN, runtime.phase());
        runtime.tick(12_999);
        assertEquals(MatchPhase.COUNTDOWN, runtime.view(12_999).phase());

        runtime.tick(13_000);
        assertEquals(MatchPhase.RUNNING, runtime.phase());
        assertEquals(MatchPhase.RUNNING, runtime.view(13_000).phase());
        assertEquals(30, runtime.view(13_000).remainingSeconds());

        runtime.tick(43_000);
        assertEquals(MatchPhase.FINISHED, runtime.view(43_000).phase());
        assertEquals(FinishReason.TIMEOUT, runtime.view(43_000).finishReason());
    }

    @Test
    void delayedTickStartsTimeLimitFromActualRunningTime() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 8, 3, 30);
        runtime.join(ALICE, 10_000);

        runtime.tick(20_000);
        MatchView view = runtime.view(20_000);
        assertEquals(MatchPhase.RUNNING, view.phase());
        assertEquals(30, view.remainingSeconds());
    }

    @Test
    void assignsStableFinishRanksAndRejectsDuplicateFinish() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 2, 8, 1, 300);
        runtime.join(ALICE, 0);
        runtime.join(BOB, 0);
        runtime.tick(1_000);

        assertEquals(1, runtime.finish(ALICE, 4_000).orElseThrow());
        assertEquals(1, runtime.finish(ALICE, 4_100).orElseThrow());
        assertEquals(2, runtime.finish(BOB, 5_000).orElseThrow());
        assertEquals(MatchPhase.FINISHED, runtime.view(5_000).phase());
        assertEquals(FinishReason.ALL_FINISHED, runtime.view(5_000).finishReason());
    }

    @Test
    void enforcesCapacityAndRunningJoinLock() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 1, 1, 300);
        assertEquals(JoinResult.JOINED, runtime.join(ALICE, 0));
        assertEquals(JoinResult.FULL, runtime.join(BOB, 0));
        runtime.tick(1_000);
        assertEquals(JoinResult.RUNNING, runtime.join(BOB, 1_001));
        assertFalse(runtime.leave(UUID.randomUUID()));
    }

    @Test
    void reportsEmptyAfterLastPlayerLeavesAndDoesNotOverwriteFinishReason() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 8, 1, 300);
        runtime.join(ALICE, 0);
        assertFalse(runtime.isEmpty());
        assertTrue(runtime.leave(ALICE));
        assertTrue(runtime.isEmpty());

        runtime.cancel();
        assertEquals(FinishReason.CANCELLED, runtime.view(1).finishReason());
        runtime.cancel();
        assertEquals(FinishReason.CANCELLED, runtime.view(2).finishReason());
    }

    @Test
    void resetsExpiredCountdownWhenMinimumPlayersAreNoLongerPresent() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 2, 8, 1, 300);
        runtime.join(ALICE, 0);
        runtime.join(BOB, 0);
        assertEquals(MatchPhase.COUNTDOWN, runtime.phase());
        runtime.leave(BOB);
        runtime.tick(2_000);
        assertEquals(MatchPhase.WAITING, runtime.phase());
        runtime.join(BOB, 3_000);
        assertEquals(MatchPhase.COUNTDOWN, runtime.phase());
        assertEquals(1, runtime.view(3_000).countdownSeconds());
    }

    @Test
    void rejectsNullPlayerIdsInsteadOfCreatingUnrepresentableSnapshots() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 8, 1, 300);

        assertThrows(NullPointerException.class, () -> runtime.join(null, 0));
        assertThrows(NullPointerException.class, () -> runtime.finish(null, 0));
    }

    @Test
    void keepsFinishRanksStableWhenACompletedPlayerLeaves() {
        ArenaRuntime runtime = new ArenaRuntime("summer", 1, 8, 1, 300);
        runtime.join(ALICE, 0);
        runtime.join(BOB, 0);
        runtime.tick(1_000);

        assertEquals(1, runtime.finish(ALICE, 2_000).orElseThrow());
        assertTrue(runtime.leave(ALICE));
        assertEquals(2, runtime.finish(BOB, 3_000).orElseThrow());
        assertEquals(2, runtime.view(3_000).ranks().get(BOB));
    }
}
