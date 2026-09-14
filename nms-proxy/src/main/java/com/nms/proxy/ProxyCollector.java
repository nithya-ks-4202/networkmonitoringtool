package com.nms.proxy;

import com.nms.collector.PollerRegistry;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.protocol.ProxyConfigResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the local network on the schedule the server handed down.
 *
 * <p>Scheduling is kept in memory here rather than in a database, because the
 * proxy has none. Losing the schedule on restart is harmless: every item is
 * simply due immediately and the next cycle re-establishes the spread.
 */
@Component
public class ProxyCollector {

    private static final Logger log = LoggerFactory.getLogger(ProxyCollector.class);

    /** Assumed when the server's configuration does not specify one. */
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

    private final ProxyProperties properties;
    private final ServerClient serverClient;
    private final PollerRegistry pollers;
    private final ResultSpool spool;

    /** The current assignment, replaced wholesale when the server revises it. */
    private final Map<Long, ScheduledCheck> assignment = new ConcurrentHashMap<>();

    private volatile long configRevision = -1;
    private volatile String version = "1.0.0";

    private ThreadPoolExecutor workers;

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public ProxyCollector(ProxyProperties properties,
                          ServerClient serverClient,
                          PollerRegistry pollers,
                          ResultSpool spool) {
        this.properties = properties;
        this.serverClient = serverClient;
        this.pollers = pollers;
        this.spool = spool;
    }

    @PostConstruct
    void start() {
        workers = new ThreadPoolExecutor(
                properties.getPollerThreads(), properties.getPollerThreads(),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getPollerThreads() * 20),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("proxy-poller-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                });

        serverClient.enrol(version).ifPresentOrElse(
                result -> log.info("Enrolled with the server as '{}' (id {})",
                        result.proxyName(), result.proxyId()),
                () -> log.warn("Could not enrol on startup; will keep trying"));
    }

    /** Refreshes the item assignment when the server's revision has moved. */
    @Scheduled(fixedDelayString = "#{@proxyProperties.configInterval.toMillis()}")
    public void refreshConfiguration() {
        serverClient.fetchConfiguration(configRevision).ifPresent(this::applyConfiguration);
    }

    private void applyConfiguration(ProxyConfigResponse response) {
        if (!response.changed()) {
            return;
        }

        // Existing due times are preserved across a configuration change, so
        // editing one host does not restart the polling cycle of every other
        // item and produce a burst of simultaneous checks.
        Map<Long, Instant> previousDueTimes = new ConcurrentHashMap<>();
        assignment.forEach((itemId, check) -> previousDueTimes.put(itemId, check.dueAt()));

        assignment.clear();
        for (CheckRequest request : response.items()) {
            Instant dueAt = previousDueTimes.getOrDefault(request.itemId(), Instant.now());
            assignment.put(request.itemId(), new ScheduledCheck(request, dueAt));
        }

        configRevision = response.revision();
        log.info("Applied configuration revision {}: {} item(s) assigned",
                configRevision, assignment.size());
    }

    /** Dispatches whatever is due. */
    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (assignment.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (ScheduledCheck check : assignment.values()) {
            if (check.dueAt().isAfter(now)) {
                continue;
            }

            // Rescheduled before dispatch, so a check that outruns its own
            // interval is not queued again on the very next pass.
            check.scheduleNext(now, intervalFor(check.request()));

            try {
                workers.execute(() -> execute(check.request()));
            } catch (RejectedExecutionException e) {
                log.warn("Proxy poller queue is full; deferring item {} ({})",
                        check.request().itemId(), check.request().key());
            }
        }
    }

    private void execute(CheckRequest request) {
        try {
            CheckResult result = pollers.poll(request);
            spool.add(result);
            polled.incrementAndGet();
            if (!result.isSuccess()) {
                failed.incrementAndGet();
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.error("Unexpected failure collecting item {} ({})",
                    request.itemId(), request.key(), e);
            spool.add(CheckResult.failed(request.itemId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * The interval for a check.
     *
     * <p>Carried in the request's parameters by the server, because the proxy
     * has no item table to read it from.
     */
    private Duration intervalFor(CheckRequest request) {
        int seconds = request.intParam("delaySeconds", 0);
        return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_INTERVAL;
    }

    /** Uploads buffered results, returning them to the buffer if that fails. */
    @Scheduled(fixedDelayString = "#{@proxyProperties.uploadInterval.toMillis()}")
    public void upload() {
        List<CheckResult> batch = spool.take(properties.getUploadBatchSize());
        if (batch.isEmpty()) {
            return;
        }

        serverClient.upload(batch, spool.depth(), version).ifPresentOrElse(
                acknowledgement -> {
                    if (acknowledgement.configRevision() != configRevision) {
                        // The server noticed our configuration is stale while
                        // acknowledging data, which saves waiting for the next
                        // configuration poll to find out.
                        log.info("Server reports configuration revision {}; refreshing",
                                acknowledgement.configRevision());
                        refreshConfiguration();
                    }
                },
                // Put back rather than discarded: the upload failed because the
                // network was unavailable, not because the measurements were
                // wrong.
                () -> spool.returnToBuffer(batch));
    }

    /** Items currently assigned to this proxy. */
    public int assignedItemCount() {
        return assignment.size();
    }

    public long polledCount() {
        return polled.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long currentRevision() {
        return configRevision;
    }

    @PreDestroy
    void stop() {
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Anything still buffered goes to disk, so a restart during an outage
        // does not throw away what was collected before it.
        spool.flush();
        log.info("Proxy collector stopped after {} checks ({} failed)", polled.get(), failed.get());
    }

    /**
     * One assigned check and when it is next due.
     *
     * <p>Mutable rather than a record because the due time is updated on every
     * dispatch and allocating a replacement per check per cycle would be
     * needless garbage at a few thousand items.
     */
    private static final class ScheduledCheck {
        private final CheckRequest request;
        private volatile Instant dueAt;

        ScheduledCheck(CheckRequest request, Instant dueAt) {
            this.request = request;
            this.dueAt = dueAt;
        }

        CheckRequest request() {
            return request;
        }

        Instant dueAt() {
            return dueAt;
        }

        void scheduleNext(Instant now, Duration interval) {
            this.dueAt = now.plus(interval);
        }
    }
}
