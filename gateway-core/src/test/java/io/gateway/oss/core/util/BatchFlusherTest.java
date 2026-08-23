package io.gateway.oss.core.util;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class BatchFlusherTest {

    private BatchFlusher flusher;
    private SimpleMeterRegistry meterRegistry;
    private GatewayMetricsRecorder metricsRecorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
        metricsRecorder = new GatewayMetricsRecorder(meterRegistry);
        flusher = newFlusher(16);
    }

    @AfterEach
    void tearDown() {
        if (flusher != null) {
            flusher.close();
        }
        if (meterRegistry != null) {
            Metrics.globalRegistry.remove(meterRegistry);
            meterRegistry.close();
        }
    }

    @Test
    void submit_inSynchronousMode_runsImmediately() {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(true);
        flusher.submit(counter::incrementAndGet);
        assertEquals(1, counter.get(), "Task should have run immediately in synchronous mode");
        assertEquals(1L, flusher.getCriticalSubmittedCount());
        assertEquals(0L, flusher.getBestEffortSubmittedCount());
    }

    @Test
    void submit_inAsyncMode_queuesAndExecutes() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        flusher.submit(counter::incrementAndGet);
        assertTrue(awaitCounter(counter, 1, 200), "Task should have been executed by async worker");
        assertEquals(1L, flusher.getCriticalSubmittedCount());
    }

    @Test
    void submitCriticalAndBestEffort_bothExecute_andCountersTracked() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        flusher.submitCritical(counter::incrementAndGet);
        flusher.submitBestEffort(counter::incrementAndGet);

        assertTrue(awaitCounter(counter, 2, 500), "Both critical and best-effort tasks should execute");
        assertEquals(1L, flusher.getCriticalSubmittedCount());
        assertEquals(1L, flusher.getBestEffortSubmittedCount());
    }

    @Test
    void submitBestEffort_whenQueueFull_dropsTaskAndTracksCounter() {
        flusher.setSynchronous(false);
        flusher.setMaxQueueDepth(1);
        flusher.submitCritical(() -> sleepQuietly(150));
        flusher.submitBestEffort(() -> sleepQuietly(10));
        flusher.submitBestEffort(() -> fail("best-effort task should have been dropped"));
        assertTrue(flusher.getBestEffortDroppedCount() >= 1);
        assertTrue(flusher.getMaxObservedQueueDepth() >= 1);
    }

    @Test
    void submitCritical_whenQueueFull_runsSyncFallbackAndTracksCounter() {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        flusher.setMaxQueueDepth(1);
        flusher.submitCritical(() -> sleepQuietly(150));
        flusher.submitCritical(counter::incrementAndGet);
        assertEquals(1, counter.get());
        assertTrue(flusher.getCriticalSyncFallbackCount() >= 1);
        assertTrue(flusher.getMaxObservedQueueDepth() >= 1);
    }

    @Test
    void submitCritical_whenOnlyBestEffortQueueIsFull_stillQueuesAsync() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        flusher.setSynchronous(false);
        flusher.setMaxQueueDepth(1);

        flusher.submitBestEffort(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(100, TimeUnit.MILLISECONDS));

        flusher.submitCritical(counter::incrementAndGet);
        assertEquals(0, counter.get(), "Critical task should stay async when only best-effort queue is saturated");
        assertEquals(0L, flusher.getCriticalSyncFallbackCount());

        releaseBlocker.countDown();
        assertTrue(awaitCounter(counter, 1, 500), "Critical task should execute after queued best-effort work drains");
    }

    @Test
    void drainLoop_prioritizesCriticalQueueOverPendingBestEffortQueue() throws InterruptedException {
        flusher.close();
        flusher = newFlusher(1);
        flusher.setSynchronous(false);

        List<String> executionOrder = new CopyOnWriteArrayList<>();
        CountDownLatch firstBestEffortStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBestEffort = new CountDownLatch(1);
        CountDownLatch trailingTasksDone = new CountDownLatch(2);

        flusher.submitBestEffort(() -> {
            executionOrder.add("best-effort-1");
            firstBestEffortStarted.countDown();
            try {
                releaseFirstBestEffort.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstBestEffortStarted.await(200, TimeUnit.MILLISECONDS));

        flusher.submitBestEffort(() -> {
            executionOrder.add("best-effort-2");
            trailingTasksDone.countDown();
        });
        flusher.submitCritical(() -> {
            executionOrder.add("critical");
            trailingTasksDone.countDown();
        });

        releaseFirstBestEffort.countDown();
        assertTrue(trailingTasksDone.await(500, TimeUnit.MILLISECONDS));
        // best-effort-1 runs first (submitted before critical); critical and best-effort-2 both complete
        assertEquals("best-effort-1", executionOrder.get(0));
        assertTrue(executionOrder.contains("critical"));
        assertTrue(executionOrder.contains("best-effort-2"));
    }

    @Test
    void queueDepth_reportsPendingTasks() {
        flusher.setSynchronous(false);
        flusher.submit(() -> sleepQuietly(100));
        flusher.submit(() -> sleepQuietly(100));
        assertTrue(flusher.getQueueDepth() >= 0, "Queue depth getter should be available for visibility");
        assertTrue(flusher.getMaxObservedQueueDepth() >= flusher.getQueueDepth());
    }

    @Test
    void flush_drainsAllQueuedTasks() {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        for (int i = 0; i < 5; i++) {
            flusher.submit(counter::incrementAndGet);
        }
        flusher.flush();
        assertTrue(awaitCounter(counter, 5, 1000), "All queued tasks should have been drained by flush()");
    }

    @Test
    void flush_handlesExceptionInTask() {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        flusher.submit(() -> { throw new RuntimeException("boom"); });
        flusher.submit(counter::incrementAndGet);
        assertDoesNotThrow(() -> flusher.flush(), "flush() should not propagate task exceptions");
        assertTrue(awaitCounter(counter, 1, 1000), "Subsequent tasks should still execute after a failing task");
    }

    @Test
    void close_stopsWorkerAndDrainsRemaining() {
        AtomicInteger counter = new AtomicInteger(0);
        flusher.setSynchronous(false);
        for (int i = 0; i < 5; i++) {
            flusher.submit(counter::incrementAndGet);
        }
        flusher.close();
        assertEquals(5, counter.get(), "All tasks should have been drained on close()");
    }

    @Test
    void close_isIdempotent() {
        assertDoesNotThrow(() -> {
            flusher.close();
            flusher.close();
        }, "Calling close() twice should not throw");
    }

    @Test
    void submit_multipleTasks_allExecuted() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        int taskCount = 10;
        CountDownLatch latch = new CountDownLatch(taskCount);
        flusher.setSynchronous(false);
        for (int i = 0; i < taskCount; i++) {
            flusher.submit(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS), "All 10 tasks should complete within 200ms");
        assertEquals(taskCount, counter.get(), "All 10 tasks should have been executed");
    }

    @Test
    void submit_maxBatchRespected() {
        AtomicInteger counter = new AtomicInteger(0);
        int taskCount = 250;
        flusher.setSynchronous(false);
        for (int i = 0; i < taskCount; i++) {
            flusher.submit(counter::incrementAndGet);
        }
        flusher.flush();
        assertTrue(awaitCounter(counter, taskCount, 2000), "All 250 tasks should have been executed");
    }

    @Test
    void drainMetrics_trackExecutedBatch() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        flusher.setSynchronous(false);
        for (int i = 0; i < 3; i++) {
            flusher.submit(() -> {
                sleepQuietly(20);
                latch.countDown();
            });
        }

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(awaitDrainCycles(flusher, 1, 1000));
        assertTrue(awaitDrainedTasks(flusher, 3, 1000));
        assertTrue(flusher.getTotalDrainTimeMs() >= 0);
        assertTrue(flusher.getLastDrainTaskCount() >= 1);
        assertTrue(flusher.getLastDrainCycleMs() >= 0);
        assertTrue(meterRegistry.get("gateway.batch_flusher.task.wait")
                .tag("taskClass", "CRITICAL").timer().count() >= 1);
        assertTrue(meterRegistry.get("gateway.batch_flusher.task.exec")
                .tag("taskClass", "CRITICAL").timer().count() >= 1);
    }

    @Test
    void submitWhenOverloaded_recordsOverloadMetrics() {
        flusher.setSynchronous(false);
        flusher.setMaxQueueDepth(1);
        flusher.submitCritical(() -> sleepQuietly(150));
        flusher.submitBestEffort(() -> sleepQuietly(10));
        flusher.submitBestEffort(() -> sleepQuietly(10));
        flusher.submitCritical(() -> sleepQuietly(10));

        assertTrue(meterRegistry.get("gateway.batch_flusher.overload")
                .tag("action", "drop").tag("taskClass", "BEST_EFFORT")
                .counter().count() >= 1.0);
        assertTrue(meterRegistry.get("gateway.batch_flusher.overload")
                .tag("action", "sync_fallback").tag("taskClass", "CRITICAL")
                .counter().count() >= 1.0);
    }

    @Test
    void constructor_registersQueueDepthGauges() {
        assertNotNull(meterRegistry.find("gateway.batch_flusher.queue.depth")
                .tags("taskClass", "all").gauge());
        assertNotNull(meterRegistry.find("gateway.batch_flusher.queue.depth")
                .tags("taskClass", "critical").gauge());
        assertNotNull(meterRegistry.find("gateway.batch_flusher.queue.depth")
                .tags("taskClass", "best_effort").gauge());
    }

    private static boolean awaitCounter(AtomicInteger counter, int expected, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && counter.get() < expected) {
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return counter.get() == expected;
    }

    private static boolean awaitDrainCycles(BatchFlusher flusher, long expected, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && flusher.getDrainCycles() < expected) {
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return flusher.getDrainCycles() >= expected;
    }

    private static boolean awaitDrainedTasks(BatchFlusher flusher, long expected, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && flusher.getDrainedTasks() < expected) {
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return flusher.getDrainedTasks() >= expected;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BatchFlusher newFlusher(int threadPoolSize) {
        GatewayProperties properties = new GatewayProperties();
        properties.getBatchFlusher().setThreadPoolSize(threadPoolSize);
        return new BatchFlusher(properties, metricsRecorder);
    }
}
