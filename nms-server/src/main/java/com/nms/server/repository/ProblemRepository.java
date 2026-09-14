package com.nms.server.repository;

import com.nms.common.Severity;
import com.nms.server.domain.Problem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, Long> {

    /**
     * The open problem for a trigger, if any.
     *
     * <p>Used before raising a new one so a trigger that stays in PROBLEM does
     * not accumulate a problem per evaluation.
     */
    @Query("""
            SELECT p FROM Problem p
            WHERE p.objectType = com.nms.server.domain.EventObjectType.TRIGGER
              AND p.objectId = :triggerId
              AND p.resolvedAt IS NULL
            """)
    Optional<Problem> findOpenByTriggerId(@Param("triggerId") Long triggerId);

    @Query("""
            SELECT p FROM Problem p
            WHERE p.objectType = com.nms.server.domain.EventObjectType.TRIGGER
              AND p.objectId = :triggerId
              AND p.resolvedAt IS NULL
            """)
    List<Problem> findAllOpenByTriggerId(@Param("triggerId") Long triggerId);

    @Query("""
            SELECT p FROM Problem p
            LEFT JOIN FETCH p.host
            WHERE p.tenantId = :tenantId
              AND p.resolvedAt IS NULL
              AND p.severity >= :minSeverity
            ORDER BY p.severity DESC, p.clock DESC
            """)
    List<Problem> findOpen(@Param("tenantId") Long tenantId,
                           @Param("minSeverity") Severity minSeverity,
                           Pageable pageable);

    @Query("""
            SELECT p FROM Problem p
            LEFT JOIN FETCH p.host
            WHERE p.tenantId = :tenantId
              AND (:onlyOpen = false OR p.resolvedAt IS NULL)
              AND (:hostId IS NULL OR p.host.id = :hostId)
              AND p.clock >= :since
            ORDER BY p.clock DESC
            """)
    Page<Problem> search(@Param("tenantId") Long tenantId,
                         @Param("onlyOpen") boolean onlyOpen,
                         @Param("hostId") Long hostId,
                         @Param("since") Instant since,
                         Pageable pageable);

    @Query("""
            SELECT p.severity, count(p) FROM Problem p
            WHERE p.tenantId = :tenantId
              AND p.resolvedAt IS NULL
              AND p.suppressed = false
            GROUP BY p.severity
            """)
    List<Object[]> countOpenBySeverity(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT p FROM Problem p
            WHERE p.host.id = :hostId
              AND p.resolvedAt IS NULL
            ORDER BY p.severity DESC
            """)
    List<Problem> findOpenByHostId(@Param("hostId") Long hostId);

    /** Open problems on hosts covered by a maintenance window, for suppression. */
    @Query("""
            SELECT p FROM Problem p
            WHERE p.resolvedAt IS NULL
              AND p.host.id IN :hostIds
            """)
    List<Problem> findOpenForHosts(@Param("hostIds") List<Long> hostIds);

    /** Problems whose operator-set suppression has run out. */
    @Query("""
            SELECT p FROM Problem p
            WHERE p.suppressed = true
              AND p.suppressedUntil IS NOT NULL
              AND p.suppressedUntil <= :now
            """)
    List<Problem> findExpiredSuppressions(@Param("now") Instant now);
}
