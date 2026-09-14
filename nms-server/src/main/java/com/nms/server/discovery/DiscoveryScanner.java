package com.nms.server.discovery;

import com.nms.server.domain.DiscoveryCheck;
import com.nms.server.domain.DiscoveryRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sweeps address ranges on a schedule, looking for devices.
 *
 * <p>Deliberately unhurried. This is the one part of the platform that sends
 * traffic to machines it has never been told about, and a fast sweep of an
 * unfamiliar network is indistinguishable from a port scan -- which will get
 * the monitoring server blocked by anything that is paying attention. The
 * per-rule concurrency limit is the control, and its default is low.
 *
 * <p>Nothing is created automatically. A sweep records what answered; a
 * person decides what to monitor. Auto-creation reads as a convenience right
 * up to the scan that adds three hundred laptops to the estate.
 */
@Component
public class DiscoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryScanner.class);

    /**
     * Rules started per pass. One at a time: a sweep is long and low
     * priority, and running several ranges at once multiplies the traffic
     * this is trying to keep modest.
     */
    private static final int RULES_PER_PASS = 1;

    private final DiscoveryStore store;
    private final AddressProbe probe;
    private final boolean enabled;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Guards against a second pass starting while one is still sweeping. */
    private final Semaphore sweepSlot = new Semaphore(1);

    public DiscoveryScanner(DiscoveryStore store,
                            AddressProbe probe,
                            @Value("${nms.discovery.enabled:true}") boolean enabled) {
        this.store = store;
        this.probe = probe;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${nms.discovery.poll-interval-ms:30000}")
    public void run() {
        if (!enabled) {
            return;
        }

        // tryAcquire, not acquire: if a sweep is still going, this pass has
        // nothing to do and should return rather than queue up behind it.
        if (!sweepSlot.tryAcquire()) {
            return;
        }

        try {
            for (DiscoveryRule rule : store.claimDue(RULES_PER_PASS)) {
                sweep(rule);
            }
        } catch (RuntimeException e) {
            log.error("Discovery pass failed: {}", e.getMessage(), e);
        } finally {
            sweepSlot.release();
        }
    }

    private void sweep(DiscoveryRule rule) {
        IpRange range;
        try {
            range = IpRange.parse(rule.getIpRange());
        } catch (IllegalArgumentException e) {
            // A configuration fault, not a network one. Logged plainly and
            // not retried in a tight loop: the rule was already rescheduled
            // when it was claimed.
            log.warn("Discovery rule '{}' has an unusable range: {}", rule.getName(), e.getMessage());
            return;
        }

        List<DiscoveryCheck> checks = rule.getChecks();
        if (checks.isEmpty()) {
            log.warn("Discovery rule '{}' has no checks, so it can find nothing", rule.getName());
            return;
        }

        Instant startedAt = Instant.now();
        log.info("Discovery rule '{}': sweeping {} address(es), {} at a time",
                rule.getName(), range.size(), rule.getConcurrency());

        AtomicInteger found = new AtomicInteger();
        Semaphore inFlight = new Semaphore(Math.max(1, rule.getConcurrency()));

        // Virtual threads, so the concurrency limit is what actually bounds
        // the sweep rather than a pool size chosen for carrier threads. Each
        // probe is almost entirely waiting on a socket.
        for (String address : range.addresses()) {
            try {
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Discovery rule '{}' interrupted; stopping sweep", rule.getName());
                return;
            }

            workers.execute(() -> {
                try {
                    Map<String, String> results = probe.probe(address, checks);
                    if (!results.isEmpty()) {
                        store.recordSighting(rule.getId(), address, results);
                        found.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    log.debug("Probe of {} failed: {}", address, e.toString());
                } finally {
                    inFlight.release();
                }
            });
        }

        // Waits for the stragglers by reacquiring every permit.
        try {
            inFlight.acquire(Math.max(1, rule.getConcurrency()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        store.markMissing(rule.getId(), startedAt);

        log.info("Discovery rule '{}': {} device(s) answered, took {}",
                rule.getName(), found.get(),
                DiscoveryStore.humanise(Duration.between(startedAt, Instant.now())));
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Discovery workers did not stop within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
