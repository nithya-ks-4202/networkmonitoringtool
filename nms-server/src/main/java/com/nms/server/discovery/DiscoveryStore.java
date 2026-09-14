package com.nms.server.discovery;

import com.nms.server.domain.DiscoveredHost;
import com.nms.server.domain.DiscoveredHostStatus;
import com.nms.server.domain.DiscoveryRule;
import com.nms.server.repository.DiscoveryRepositories.DiscoveredHostRepository;
import com.nms.server.repository.DiscoveryRepositories.DiscoveryRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The transactional half of discovery.
 *
 * <p>A separate bean from {@link DiscoveryScanner} for two reasons. Spring's
 * transaction support works through a proxy, so a {@code @Transactional}
 * method called from another method of the same class runs with no
 * transaction at all. And a sweep takes minutes: holding one transaction open
 * across it would pin a connection and a snapshot for the duration, which on
 * a busy database is how a monitoring system becomes the thing that needs
 * monitoring.
 */
@Component
public class DiscoveryStore {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryStore.class);

    private final DiscoveryRuleRepository rules;
    private final DiscoveredHostRepository discovered;

    public DiscoveryStore(DiscoveryRuleRepository rules, DiscoveredHostRepository discovered) {
        this.rules = rules;
        this.discovered = discovered;
    }

    /**
     * Claims the rules due to run and pushes their next run time forward
     * before releasing them.
     *
     * <p>Rescheduled at claim time rather than on completion, so a sweep that
     * crashes or a server that is killed mid-scan does not leave a rule that
     * re-runs immediately and forever.
     */
    @Transactional
    public List<DiscoveryRule> claimDue(int limit) {
        Instant now = Instant.now();
        List<DiscoveryRule> due = rules.claimDueRules(now, limit);

        for (DiscoveryRule rule : due) {
            rules.reschedule(rule.getId(), now.plusSeconds(Math.max(60, rule.getDelaySeconds())));
            // The checks are read inside the transaction: the scan runs
            // outside it, where a lazy collection would fail.
            rule.getChecks().size();
        }
        return due;
    }

    /**
     * Records one device sighting.
     *
     * <p>Its own transaction per address, so a sweep's findings are visible
     * as it progresses rather than all at once at the end. On a large range
     * that is the difference between an operator watching devices appear and
     * one staring at an empty table wondering whether it is working.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSighting(Long ruleId, String ip, Map<String, String> results) {
        Instant now = Instant.now();

        Optional<DiscoveredHost> existing = discovered.findByRuleIdAndIp(ruleId, ip);
        DiscoveredHost device = existing.orElseGet(() -> {
            DiscoveredHost created = new DiscoveredHost();
            created.setRule(rules.getReferenceById(ruleId));
            created.setIp(ip);
            created.setFirstSeenAt(now);
            return created;
        });

        device.setStatus(DiscoveredHostStatus.UP);
        device.setLastSeenAt(now);
        device.setCheckResults(new HashMap<>(results));

        discovered.save(device);
    }

    /** Flags devices that were seen before but did not answer this sweep. */
    @Transactional
    public int markMissing(Long ruleId, Instant sweepStartedAt) {
        int marked = discovered.markMissingAsDown(ruleId, sweepStartedAt, Instant.now());
        if (marked > 0) {
            log.info("Discovery rule {}: {} previously seen device(s) did not answer", ruleId, marked);
        }
        return marked;
    }

    /** Used by the scanner to report how long a sweep took. */
    static String humanise(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }
}
