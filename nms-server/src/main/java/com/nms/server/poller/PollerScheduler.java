package com.nms.server.poller;

import com.nms.collector.PollerRegistry;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives collection: claims items that are due and hands them to workers.
 *
 * <p>Work is claimed from the database with {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} rather than partitioned in advance. That is what lets several server
 * instances share one queue with no leader election and no coordination: each
 * takes rows the others have not locked. An instance that dies mid-batch simply
 * releases its locks and the next pass picks the work up.
 *
 * <p>The worker pool is sized far above the core count because a check spends
 * almost all of its time waiting on a network reply. Fifty threads hold fifty
 * devices in flight while using very little CPU.
 */
@Component
@ConditionalOnProperty(name = "nms.poller.enabled", havingValue = "true", matchIfMissing = true)
public class PollerScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollerScheduler.class);

    private final ItemClaimer claimer;
    private final PollerRegistry pollers;
    private final ValueProcessor valueProcessor;

    private final int threads;
    private final int batchSize;
    private final Duration maxCheckDuration;

    private ThreadPoolExecutor workers;

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();

    public PollerScheduler(ItemClaimer claimer,
                           PollerRegistry pollers,
                           ValueProcessor valueProcessor,
                           @Value("${nms.poller.threads:50}") int threads,
                           @Value("${nms.poller.batch-size:500}") int batchSize,
                           @Value("${nms.poller.max-check-seconds:30}") int maxCheckSeconds) {
        this.claimer = claimer;
        this.pollers = pollers;
        this.valueProcessor = valueProcessor;
        this.threads = threads;
        this.batchSize = batchSize;
        this.maxCheckDuration = Duration.ofSeconds(maxCheckSeconds);
    }

    @PostConstruct
    void start() {
        workers = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                // Bounded: a dispatch pass must not be able to queue unbounded
                // work while the pool is already saturated. A rejection is
                // visible and recoverable; unbounded growth is neither.
                new LinkedBlockingQueue<>(batchSize * 4),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("poller-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                });
        log.info("Poller started with {} workers, batches of {}", threads, batchSize);
    }

    /**
     * One dispatch pass.
     *
     * <p>Fixed delay rather than fixed rate: when a pass overruns because the
     * database is slow, the next starts after it rather than piling up behind.
     */
    @Scheduled(fixedDelayString = "${nms.poller.dispatch-interval-ms:1000}")
    public void dispatch() {
        try {
            List<CheckRequest> requests = claimer.claim(batchSize);
            for (CheckRequest request : requests) {
                try {
                    workers.execute(() -> execute(request));
                } catch (RejectedExecutionException e) {
                    // The item keeps the next-check time it was just given and
                    // will be picked up later. Counting this is what makes a
                    // persistently undersized pool visible.
                    long total = deferred.incrementAndGet();
                    if (total == 1 || total % 1000 == 0) {
                        log.warn("Poller queue is full; {} checks deferred so far. "
                                + "Consider raising nms.poller.threads.", total);
                    }
                }
            }
        } catch (RuntimeException e) {
            // A scheduled method that propagates an exception may not be
            // rescheduled; catching here guarantees the next pass runs.
            log.error("Poller dispatch pass failed: {}", e.getMessage(), e);
        }
    }

    private void execute(CheckRequest request) {
        long startNanos = System.nanoTime();
        try {
            CheckResult result = pollers.poll(request);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            if (elapsed.compareTo(maxCheckDuration) > 0) {
                log.warn("Check of item {} ({} on {}) took {}s, beyond the {}s budget",
                        request.itemId(), request.key(), request.address(),
                        elapsed.toSeconds(), maxCheckDuration.toSeconds());
            }

            valueProcessor.accept(result);
            polled.incrementAndGet();
            if (!result.isSuccess()) {
                failed.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // PollerRegistry already absorbs poller faults, so anything
            // reaching here is a defect in the pipeline -- which must still
            // not take the worker thread down with it.
            failed.incrementAndGet();
            log.error("Unexpected failure collecting item {} ({})",
                    request.itemId(), request.key(), e);
            valueProcessor.recordFailure(request.itemId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Checks completed since start. */
    public long polledCount() {
        return polled.get();
    }

    /** Checks that returned a failure since start. */
    public long failedCount() {
        return failed.get();
    }

    /** Checks dropped because no worker was free. */
    public long deferredCount() {
        return deferred.get();
    }

    /** Work waiting for a free worker; a sustained rise means the pool is small. */
    public int queueDepth() {
        return workers == null ? 0 : workers.getQueue().size();
    }

    public int activeWorkers() {
        return workers == null ? 0 : workers.getActiveCount();
    }

    @PreDestroy
    void stop() {
        if (workers == null) {
            return;
        }
        workers.shutdown();
        try {
            // Let in-flight checks finish so their values survive a rolling
            // restart rather than being discarded mid-collection.
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Poller stopped after {} checks ({} failed)", polled.get(), failed.get());
    }
}
