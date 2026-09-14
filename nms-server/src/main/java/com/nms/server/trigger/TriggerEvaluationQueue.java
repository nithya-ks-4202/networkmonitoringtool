package com.nms.server.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queues trigger evaluation so it happens off the poller's threads.
 *
 * <p>Evaluating a trigger reads a window of history, which is a database round
 * trip. Doing that inline would occupy a poller worker for the duration and cut
 * collection throughput for work that does not need to be synchronous.
 *
 * <p>Consecutive submissions for the same item are collapsed within a batch. A
 * switch with two hundred interface items feeding one aggregate trigger would
 * otherwise evaluate that trigger two hundred times for the same conclusion.
 */
@Component
public class TriggerEvaluationQueue {

    private static final Logger log = LoggerFactory.getLogger(TriggerEvaluationQueue.class);

    private final TriggerEvaluator evaluator;
    private final BlockingQueue<Pending> queue;
    private final int workerCount;
    private final int batchSize;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong evaluated = new AtomicLong();
    private final AtomicLong collapsed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public TriggerEvaluationQueue(TriggerEvaluator evaluator,
                                  @Value("${nms.trigger.workers:4}") int workerCount,
                                  @Value("${nms.trigger.batch-size:200}") int batchSize,
                                  @Value("${nms.trigger.queue-capacity:100000}") int queueCapacity) {
        this.evaluator = evaluator;
        this.workerCount = workerCount;
        this.batchSize = batchSize;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(this::processLoop, "trigger-eval-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        log.info("Trigger evaluation started with {} workers", workerCount);
    }

    /**
     * Queues an item for trigger evaluation.
     *
     * <p>Never blocks, for the same reason the history writer does not: the
     * caller is a poller worker, and stalling it would stop collection.
     */
    public void submit(long itemId, Instant clock) {
        submitted.incrementAndGet();
        if (!queue.offer(new Pending(itemId, clock))) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 10_000 == 0) {
                log.error("Trigger evaluation queue is full; {} evaluations skipped. "
                        + "Alerting may be delayed.", total);
            }
        }
    }

    private void processLoop() {
        List<Pending> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                Pending first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);

                // Keep the newest clock per item: evaluating twice for the
                // same item in one batch would reach the same conclusion.
                Map<Long, Instant> latestPerItem = new LinkedHashMap<>();
                for (Pending pending : batch) {
                    latestPerItem.merge(pending.itemId(), pending.clock(),
                            (existing, candidate) -> candidate.isAfter(existing) ? candidate : existing);
                }
                collapsed.addAndGet(batch.size() - latestPerItem.size());
                batch.clear();

                latestPerItem.forEach(this::evaluateSafely);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Trigger evaluation batch failed: {}", e.getMessage(), e);
                batch.clear();
            }
        }
    }

    private void evaluateSafely(long itemId, Instant clock) {
        try {
            evaluator.evaluateForItem(itemId, clock);
            evaluated.incrementAndGet();
        } catch (RuntimeException e) {
            // A failure on one item must not abandon the rest of the batch.
            log.error("Failed to evaluate triggers for item {}: {}", itemId, e.getMessage(), e);
        }
    }

    /** Items waiting for evaluation; a sustained rise means alerting is lagging. */
    public int queueDepth() {
        return queue.size();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long evaluatedCount() {
        return evaluated.get();
    }

    /** Redundant evaluations avoided by collapsing duplicates. */
    public long collapsedCount() {
        return collapsed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        log.info("Trigger evaluation stopped after {} evaluations ({} collapsed, {} dropped)",
                evaluated.get(), collapsed.get(), dropped.get());
    }

    /** An item awaiting evaluation. */
    private record Pending(long itemId, Instant clock) {
    }
}
