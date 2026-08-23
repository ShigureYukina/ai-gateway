package io.gateway.oss.core.util;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BatchFlusher implements AutoCloseable {

    public enum TaskClass {
        CRITICAL,
        BEST_EFFORT
    }

    private static final Logger log = LoggerFactory.getLogger(BatchFlusher.class);
    private static final int MAX_BATCH = 200;

    private record QueuedTask(Runnable runnable, TaskClass taskClass, long enqueuedAtNs) {}

    private final Queue<QueuedTask> criticalQueue = new ConcurrentLinkedQueue<>();
    private final Queue<QueuedTask> bestEffortQueue = new ConcurrentLinkedQueue<>();
    private final int threadPoolSize;
    private final ExecutorService executor;
    private final Thread drainer;
    private final Object lock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong criticalSubmitted = new AtomicLong(0);
    private final AtomicLong bestEffortSubmitted = new AtomicLong(0);
    private final AtomicLong criticalSyncFallback = new AtomicLong(0);
    private final AtomicLong bestEffortDropped = new AtomicLong(0);
    private final AtomicLong pendingTasks = new AtomicLong(0);
    private final AtomicLong pendingCriticalTasks = new AtomicLong(0);
    private final AtomicLong pendingBestEffortTasks = new AtomicLong(0);
    private final AtomicLong maxObservedQueueDepth = new AtomicLong(0);
    private final AtomicLong drainCycles = new AtomicLong(0);
    private final AtomicLong drainedTasks = new AtomicLong(0);
    private final AtomicLong totalDrainTimeMs = new AtomicLong(0);
    private final AtomicLong lastDrainCycleGauge = new AtomicLong(0);
    private final AtomicLong lastDrainTaskCountGauge = new AtomicLong(0);
    private final GatewayMetricsRecorder metricsRecorder;
    private volatile boolean synchronous = false;
    // 该阈值用于触发退化策略（drop / sync fallback），不是严格的并发硬上限。
    private volatile int maxQueueDepth;
    private volatile long lastDrainCycleMs = 0;
    private volatile int lastDrainTaskCount = 0;

    @Autowired
    public BatchFlusher(GatewayProperties properties) {
        this(properties, new GatewayMetricsRecorder(Metrics.globalRegistry));
    }

    public BatchFlusher(GatewayProperties properties, GatewayMetricsRecorder metricsRecorder) {
        this.threadPoolSize = properties.getBatchFlusher().getThreadPoolSize();
        this.maxQueueDepth = properties.getBatchFlusher().getMaxQueueDepth();
        this.metricsRecorder = metricsRecorder;
        this.executor = Executors.newFixedThreadPool(this.threadPoolSize, r -> {
            Thread t = new Thread(r, "batch-flusher");
            t.setDaemon(true);
            return t;
        });
        registerMetrics();
        drainer = new Thread(this::drainLoop, "batch-flusher-drain");
        drainer.setDaemon(true);
        drainer.start();
    }

    public void setSynchronous(boolean sync) {
        this.synchronous = sync;
    }

    public void flush() {
        drainQueuedTasks("batch_flusher_flush_failed");
    }

    public void submit(Runnable task) {
        submit(task, TaskClass.CRITICAL);
    }

    public void submitCritical(Runnable task) {
        submit(task, TaskClass.CRITICAL);
    }

    public void submitBestEffort(Runnable task) {
        submit(task, TaskClass.BEST_EFFORT);
    }

    public void submit(Runnable task, TaskClass taskClass) {
        if (taskClass == TaskClass.BEST_EFFORT) {
            bestEffortSubmitted.incrementAndGet();
        } else {
            criticalSubmitted.incrementAndGet();
        }
        if (synchronous) {
            runSafely(task, taskClass, "batch_flusher_sync_failed", false, 0L);
            return;
        }

        long queueDepth = taskClass == TaskClass.BEST_EFFORT ? pendingTasks.get() : pendingCriticalTasks.get();
        if (queueDepth >= maxQueueDepth) {
            if (taskClass == TaskClass.BEST_EFFORT) {
                long dropped = bestEffortDropped.incrementAndGet();
                metricsRecorder.recordBatchFlusherOverload("drop", taskClass.name());
                log.warn("batch_flusher_best_effort_dropped queueDepth={} maxQueueDepth={} droppedCount={} queueDepthPeak={}",
                        queueDepth, maxQueueDepth, dropped, maxObservedQueueDepth.get());
                return;
            }
            long syncFallback = criticalSyncFallback.incrementAndGet();
            metricsRecorder.recordBatchFlusherOverload("sync_fallback", taskClass.name());
            log.warn("batch_flusher_critical_sync_fallback queueDepth={} maxQueueDepth={} fallbackCount={} queueDepthPeak={}",
                    queueDepth, maxQueueDepth, syncFallback, maxObservedQueueDepth.get());
            runSafely(task, taskClass, "batch_flusher_critical_fallback_failed", false, 0L);
            return;
        }

        long newQueueDepth = pendingTasks.incrementAndGet();
        if (taskClass == TaskClass.CRITICAL) {
            pendingCriticalTasks.incrementAndGet();
        } else {
            pendingBestEffortTasks.incrementAndGet();
        }
        updateMaxObservedQueueDepth(newQueueDepth);
        offerTask(new QueuedTask(task, taskClass, System.nanoTime()));
        synchronized (lock) {
            lock.notify();
        }
    }

    public void setMaxQueueDepth(int maxQueueDepth) {
        this.maxQueueDepth = Math.max(1, maxQueueDepth);
    }

    public long getQueueDepth() {
        return pendingTasks.get();
    }

    public long getCriticalSubmittedCount() {
        return criticalSubmitted.get();
    }

    public long getBestEffortSubmittedCount() {
        return bestEffortSubmitted.get();
    }

    public long getCriticalSyncFallbackCount() {
        return criticalSyncFallback.get();
    }

    public long getBestEffortDroppedCount() {
        return bestEffortDropped.get();
    }

    public long getMaxObservedQueueDepth() {
        return maxObservedQueueDepth.get();
    }

    public long getDrainCycles() {
        return drainCycles.get();
    }

    public long getDrainedTasks() {
        return drainedTasks.get();
    }

    public long getTotalDrainTimeMs() {
        return totalDrainTimeMs.get();
    }

    public long getLastDrainCycleMs() {
        return lastDrainCycleMs;
    }

    public int getLastDrainTaskCount() {
        return lastDrainTaskCount;
    }

    public long getCriticalQueueDepth() {
        return pendingCriticalTasks.get();
    }

    public long getBestEffortQueueDepth() {
        return pendingBestEffortTasks.get();
    }

    private void drainLoop() {
        List<QueuedTask> batch = new ArrayList<>(MAX_BATCH);
        while (running.get()) {
            try {
                batch.clear();
                drainToBatch(batch);
                if (!batch.isEmpty()) {
                    long cycleStart = System.nanoTime();
                    int taskCount = batch.size();
                    long queueDepthBeforeDrain = pendingTasks.get();
                    List<Future<?>> criticalFutures = new ArrayList<>();
                    for (QueuedTask queuedTask : batch) {
                        final QueuedTask captured = queuedTask;
                        Future<?> f = executor.submit(() -> {
                            long waitMs = elapsedMillisSince(captured.enqueuedAtNs());
                            metricsRecorder.recordBatchFlusherTaskWait(captured.taskClass().name(), waitMs);
                            runSafely(captured.runnable(), captured.taskClass(), "batch_flusher_task_failed", true, waitMs);
                        });
                        // 只 join CRITICAL 任务；BEST_EFFORT 提交后 fire-and-forget，
                        // 避免观测类任务阻塞 drain 循环，实现任务分舱隔离
                        if (queuedTask.taskClass() == TaskClass.CRITICAL) {
                            criticalFutures.add(f);
                        }
                    }
                    for (Future<?> f : criticalFutures) {
                        try { f.get(); } catch (ExecutionException e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            log.warn("batch_flusher_task_exception", cause);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    long cycleMs = (System.nanoTime() - cycleStart) / 1_000_000;
                    drainCycles.incrementAndGet();
                    drainedTasks.addAndGet(taskCount);
                    totalDrainTimeMs.addAndGet(cycleMs);
                    lastDrainCycleMs = cycleMs;
                    lastDrainTaskCount = taskCount;
                    lastDrainCycleGauge.set(cycleMs);
                    lastDrainTaskCountGauge.set(taskCount);
                    if (cycleMs > 10 || taskCount > 50) {
                        log.info(
                                "batch_flusher_cycle taskCount={} cycleMs={} queueDepthBefore={} queueDepthAfter={} queueDepthPeak={}",
                                taskCount,
                                cycleMs,
                                queueDepthBeforeDrain,
                                pendingTasks.get(),
                                maxObservedQueueDepth.get());
                    }
                } else {
                    synchronized (lock) {
                        if (isQueueEmpty()) {
                            lock.wait();
                        }
                    }
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                log.warn("batch_flusher_loop_error", e);
            }
        }
        drainAll();
    }

    private void drainAll() {
        drainQueuedTasks("batch_flusher_drain_failed");
    }

    private void drainQueuedTasks(String logCode) {
        QueuedTask task;
        while ((task = pollNextTask()) != null) {
            long waitMs = elapsedMillisSince(task.enqueuedAtNs());
            metricsRecorder.recordBatchFlusherTaskWait(task.taskClass().name(), waitMs);
            runSafely(task.runnable(), task.taskClass(), logCode, true, waitMs);
        }
    }

    private void drainToBatch(List<QueuedTask> batch) {
        drainQueueToBatch(criticalQueue, batch);
        if (batch.size() < MAX_BATCH) {
            drainQueueToBatch(bestEffortQueue, batch);
        }
    }

    private void drainQueueToBatch(Queue<QueuedTask> sourceQueue, List<QueuedTask> batch) {
        QueuedTask task;
        while (batch.size() < MAX_BATCH && (task = sourceQueue.poll()) != null) {
            batch.add(task);
        }
    }

    private QueuedTask pollNextTask() {
        QueuedTask criticalTask = criticalQueue.poll();
        return criticalTask != null ? criticalTask : bestEffortQueue.poll();
    }

    private void offerTask(QueuedTask task) {
        if (task.taskClass() == TaskClass.CRITICAL) {
            criticalQueue.offer(task);
            return;
        }
        bestEffortQueue.offer(task);
    }

    private boolean isQueueEmpty() {
        return criticalQueue.isEmpty() && bestEffortQueue.isEmpty();
    }

    private void runSafely(Runnable task, TaskClass taskClass, String logCode) {
        runSafely(task, taskClass, logCode, false, 0L);
    }

    private void runSafely(Runnable task, TaskClass taskClass, String logCode, boolean decrementPending, long waitMs) {
        long start = System.nanoTime();
        try {
            task.run();
        } catch (Exception e) {
            log.warn("{} taskClass={}", logCode, taskClass, e);
        } finally {
            long execMs = (System.nanoTime() - start) / 1_000_000;
            metricsRecorder.recordBatchFlusherTaskExecution(taskClass.name(), execMs);
            if (decrementPending) {
                pendingTasks.decrementAndGet();
                if (taskClass == TaskClass.CRITICAL) {
                    pendingCriticalTasks.decrementAndGet();
                } else {
                    pendingBestEffortTasks.decrementAndGet();
                }
            }
        }
    }

    private void registerMetrics() {
        metricsRecorder.registerGauge("gateway.batch_flusher.queue.depth",
                List.of(Tag.of("taskClass", "all")), pendingTasks);
        metricsRecorder.registerGauge("gateway.batch_flusher.queue.depth",
                List.of(Tag.of("taskClass", "critical")), pendingCriticalTasks);
        metricsRecorder.registerGauge("gateway.batch_flusher.queue.depth",
                List.of(Tag.of("taskClass", "best_effort")), pendingBestEffortTasks);
        metricsRecorder.registerGauge("gateway.batch_flusher.queue.depth.max",
                List.of(), maxObservedQueueDepth);
        metricsRecorder.registerGauge("gateway.batch_flusher.drain.last_cycle_ms",
                List.of(), lastDrainCycleGauge);
        metricsRecorder.registerGauge("gateway.batch_flusher.drain.last_task_count",
                List.of(), lastDrainTaskCountGauge);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.submitted",
                List.of(Tag.of("taskClass", "critical")), criticalSubmitted, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.submitted",
                List.of(Tag.of("taskClass", "best_effort")), bestEffortSubmitted, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.fallback.total",
                List.of(), criticalSyncFallback, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.drop.total",
                List.of(), bestEffortDropped, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.drain.cycles.total",
                List.of(), drainCycles, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.drained_tasks.total",
                List.of(), drainedTasks, AtomicLong::doubleValue);
        metricsRecorder.registerFunctionCounter("gateway.batch_flusher.drain.time.total_ms",
                List.of(), totalDrainTimeMs, AtomicLong::doubleValue);
    }

    private long elapsedMillisSince(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000);
    }

    private void updateMaxObservedQueueDepth(long queueDepth) {
        maxObservedQueueDepth.accumulateAndGet(queueDepth, Math::max);
    }

    @PreDestroy
    @Override
    public void close() {
        running.set(false);
        synchronized (lock) {
            lock.notify();
        }
        try {
            drainer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }
    }
}
