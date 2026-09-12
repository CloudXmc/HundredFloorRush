package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.scheduler.TaskHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaGenerationServiceTest {

    @Test
    void worldNamesAreStableSafeAndDistinctForChineseArenaNames() {
        String first = ArenaGenerationService.worldName("夏日一");
        String same = ArenaGenerationService.worldName("夏日一");
        String otherName = ArenaGenerationService.worldName("夏日二");
        String firstSeed = ArenaGenerationService.worldName("夏日一", 7L);
        String otherSeed = ArenaGenerationService.worldName("夏日一", 8L);

        assertEquals(first, same);
        assertNotEquals(first, otherName);
        assertNotEquals(firstSeed, otherSeed);
        assertTrue(first.matches("hfr_[a-z0-9_-]+"));
        assertTrue(first.length() <= 48);
    }

    @Test
    void longNamesKeepTheUniquenessSuffixWithinTheWorldNameLimit() {
        String first = ArenaGenerationService.worldName("abcdefghijklmnopqrstuvwxyz0123456789-long-name-one");
        String second = ArenaGenerationService.worldName("abcdefghijklmnopqrstuvwxyz0123456789-long-name-two");

        assertNotEquals(first, second);
        assertTrue(first.length() <= 48);
        assertTrue(second.length() <= 48);
    }

    @Test
    void generatedWorldNamesSeparateDifferentFloorCounts() {
        String hundred = ArenaGenerationService.worldName("summer", 7L, 100);
        String shortCourse = ArenaGenerationService.worldName("summer", 7L, 10);

        assertNotEquals(hundred, shortCourse);
        assertTrue(hundred.length() <= 63);
        assertTrue(shortCourse.length() <= 63);
    }

    @Test
    void generationContextCancelsTasksAddedBeforeAndAfterCancellation() {
        ArenaGenerationService.GenerationContext context =
                new ArenaGenerationService.GenerationContext(ignored -> {
                });
        RecordingHandle first = new RecordingHandle();
        assertTrue(context.addTask(first));

        context.requestCancellation();

        assertTrue(first.cancelled());
        assertTrue(context.isCancelledOrCompleted());

        RecordingHandle late = new RecordingHandle();
        assertFalse(context.addTask(late));
        assertTrue(late.cancelled());
    }

    @Test
    void generationContextCompletesOnlyOnceAndRetiresWithoutLeakingTasks() {
        ArenaGenerationService.GenerationContext context =
                new ArenaGenerationService.GenerationContext(ignored -> {
                });
        RecordingHandle handle = new RecordingHandle();
        assertTrue(context.addTask(handle));

        List<TaskHandle> completed = context.completeOnce();
        assertNotNull(completed);
        assertEquals(1, completed.size());
        assertNull(context.completeOnce());

        RecordingHandle late = new RecordingHandle();
        assertFalse(context.addTask(late));
        assertTrue(late.cancelled());

        ArenaGenerationService.GenerationContext retired =
                new ArenaGenerationService.GenerationContext(ignored -> {
                });
        RecordingHandle retiredHandle = new RecordingHandle();
        assertTrue(retired.addTask(retiredHandle));
        List<TaskHandle> retiredTasks = retired.retire();
        assertEquals(1, retiredTasks.size());
        assertTrue(retired.isCancelledOrCompleted());
        assertNull(retired.completeOnce());
        RecordingHandle lateRetired = new RecordingHandle();
        assertFalse(retired.addTask(lateRetired));
        assertTrue(lateRetired.cancelled());
    }

    @Test
    void generationContextConcurrentRegistrationAndCancellationCancelsEveryHandle() throws Exception {
        ArenaGenerationService.GenerationContext context =
                new ArenaGenerationService.GenerationContext(ignored -> {
                });
        List<RecordingHandle> handles = new ArrayList<>();
        List<Runnable> registrations = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 256; index++) {
                RecordingHandle handle = new RecordingHandle();
                handles.add(handle);
                registrations.add(() -> {
                    try {
                        ready.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    context.addTask(handle);
                });
            }
            registrations.forEach(executor::execute);
            ready.countDown();
            context.requestCancellation();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(handles.stream().allMatch(RecordingHandle::cancelled));
    }

    private static final class RecordingHandle implements TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }
    }
}
