package com.nms.server.repository;

import com.nms.server.domain.DiscoveredHost;
import com.nms.server.domain.DiscoveryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repositories for network discovery. */
public final class DiscoveryRepositories {

    private DiscoveryRepositories() {
    }

    @Repository
    public interface DiscoveryRuleRepository extends JpaRepository<DiscoveryRule, Long> {

        List<DiscoveryRule> findByTenantIdOrderByName(Long tenantId);

        Optional<DiscoveryRule> findByIdAndTenantId(Long id, Long tenantId);

        Optional<DiscoveryRule> findByTenantIdAndName(Long tenantId, String name);

        /**
         * Claims rules that are due to run.
         *
         * <p>Same {@code SKIP LOCKED} discipline as the poller queue, and for
         * the same reason: several server instances share one schedule, and
         * without it two of them would sweep the same range at the same time
         * -- doubling the traffic aimed at equipment that did not ask to be
         * scanned.
         *
         * <p>The lock is {@code OF r} so the join to {@code proxy} does not
         * also lock proxy rows and block the gateway behind a scan.
         */
        @Query(value = """
                SELECT r.* FROM discovery_rule r
                WHERE r.status = 'ENABLED'
                  AND (r.next_run_at IS NULL OR r.next_run_at <= :now)
                ORDER BY r.next_run_at NULLS FIRST
                LIMIT :limit
                FOR UPDATE OF r SKIP LOCKED
                """, nativeQuery = true)
        List<DiscoveryRule> claimDueRules(@Param("now") Instant now, @Param("limit") int limit);

        @Modifying
        @Query("UPDATE DiscoveryRule r SET r.nextRunAt = :nextRunAt WHERE r.id = :ruleId")
        void reschedule(@Param("ruleId") Long ruleId, @Param("nextRunAt") Instant nextRunAt);
    }

    @Repository
    public interface DiscoveredHostRepository extends JpaRepository<DiscoveredHost, Long> {

        Optional<DiscoveredHost> findByRuleIdAndIp(Long ruleId, String ip);

        List<DiscoveredHost> findByRuleIdOrderByIp(Long ruleId);

        Optional<DiscoveredHost> findByIdAndRuleTenantId(Long id, Long tenantId);

        /**
         * Everything a sweep has found for a tenant, newest sighting first.
         *
         * <p>Devices already turned into hosts are excluded by default: the
         * question this answers is "what is on my network that I am not
         * watching", and a list dominated by things already monitored does
         * not answer it.
         */
        @Query("""
                SELECT d FROM DiscoveredHost d
                JOIN FETCH d.rule r
                WHERE r.tenantId = :tenantId
                  AND (:includeMonitored = true OR d.host IS NULL)
                ORDER BY d.lastSeenAt DESC, d.ip
                """)
        List<DiscoveredHost> findForTenant(@Param("tenantId") Long tenantId,
                                           @Param("includeMonitored") boolean includeMonitored);

        /**
         * Marks devices that did not answer this sweep.
         *
         * <p>Bulk rather than row by row: a /24 that goes dark because a
         * switch failed is 254 updates, and doing those individually turns a
         * network fault into a database one.
         */
        @Modifying
        @Query("""
                UPDATE DiscoveredHost d
                SET d.status = com.nms.server.domain.DiscoveredHostStatus.DOWN,
                    d.lastDownAt = :now
                WHERE d.rule.id = :ruleId
                  AND d.lastSeenAt < :sweepStartedAt
                  AND d.status = com.nms.server.domain.DiscoveredHostStatus.UP
                """)
        int markMissingAsDown(@Param("ruleId") Long ruleId,
                              @Param("sweepStartedAt") Instant sweepStartedAt,
                              @Param("now") Instant now);

        long countByRuleIdAndHostIsNull(Long ruleId);
    }
}
