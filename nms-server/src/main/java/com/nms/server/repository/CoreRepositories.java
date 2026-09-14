package com.nms.server.repository;

import com.nms.server.domain.Action;
import com.nms.server.domain.Alert;
import com.nms.server.domain.AlertStatus;
import com.nms.server.domain.AuditLog;
import com.nms.server.domain.AvailabilitySpan;
import com.nms.server.domain.AppUser;
import com.nms.server.domain.Dashboard;
import com.nms.server.domain.Escalation;
import com.nms.server.domain.EscalationStatus;
import com.nms.server.domain.GlobalMacro;
import com.nms.server.domain.HostGroup;
import com.nms.server.domain.Maintenance;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.Proxy;
import com.nms.server.domain.Role;
import com.nms.server.domain.Tenant;
import com.nms.server.domain.UserGroup;
import com.nms.server.domain.UserMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The straightforward repositories, grouped so each does not need its own file
 * to hold three method signatures.
 *
 * <p>Interfaces with real query logic -- items, hosts, triggers, problems,
 * history -- live on their own.
 */
public final class CoreRepositories {

    private CoreRepositories() {
    }

    public interface TenantRepository extends JpaRepository<Tenant, Long> {
        Optional<Tenant> findBySlug(String slug);
    }

    public interface HostGroupRepository extends JpaRepository<HostGroup, Long> {
        Optional<HostGroup> findByTenantIdAndName(Long tenantId, String name);

        List<HostGroup> findByTenantIdOrderByName(Long tenantId);
    }

    public interface AppUserRepository extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByTenantIdAndUsername(Long tenantId, String username);

        /**
         * Loads a user with groups and role, which sign-in needs together: the
         * role carries the permissions and a disabled group denies access
         * regardless of the user's own flag.
         */
        @Query("""
                SELECT u FROM AppUser u
                LEFT JOIN FETCH u.groups
                LEFT JOIN FETCH u.role
                WHERE u.username = :username
                """)
        Optional<AppUser> findByUsernameWithAuthorities(@Param("username") String username);

        List<AppUser> findByTenantIdOrderByUsername(Long tenantId);

        boolean existsByTenantId(Long tenantId);

        /**
         * Members of the given user groups.
         *
         * <p>Escalation expands groups to individuals on every rung, so this
         * has to be an indexed join rather than a scan: loading every user to
         * filter in memory would make alerting cost grow with the size of the
         * directory rather than the size of the on-call rota.
         */
        @Query("""
                SELECT DISTINCT u FROM AppUser u
                JOIN u.groups g
                WHERE g.id IN :groupIds
                  AND u.enabled = true
                """)
        List<AppUser> findEnabledByGroupIds(@Param("groupIds") java.util.Collection<Long> groupIds);
    }

    public interface RoleRepository extends JpaRepository<Role, Long> {
        Optional<Role> findByTenantIdAndName(Long tenantId, String name);
    }

    public interface UserGroupRepository extends JpaRepository<UserGroup, Long> {
        Optional<UserGroup> findByTenantIdAndName(Long tenantId, String name);
    }

    public interface ProxyRepository extends JpaRepository<Proxy, Long> {
        Optional<Proxy> findByTenantIdAndName(Long tenantId, String name);

        /**
         * Candidates matching a token prefix.
         *
         * <p>Bcrypt hashes cannot be searched, so the clear-text prefix narrows
         * the set before any hash is verified. Checking every proxy's hash on
         * each request would be both slow and a timing oracle.
         */
        List<Proxy> findByTokenPrefix(String tokenPrefix);

        List<Proxy> findByTenantIdOrderByName(Long tenantId);

        @Modifying
        @Query("""
                UPDATE Proxy p
                SET p.lastSeenAt = :seenAt, p.version = :version,
                    p.queueDepth = :queueDepth, p.clockSkewMs = :clockSkewMs
                WHERE p.id = :proxyId
                """)
        void recordContact(@Param("proxyId") Long proxyId,
                           @Param("seenAt") Instant seenAt,
                           @Param("version") String version,
                           @Param("queueDepth") int queueDepth,
                           @Param("clockSkewMs") long clockSkewMs);

        /**
         * Invalidates a proxy's cached configuration.
         *
         * <p>Called whenever a host or item assigned to it changes, so the
         * proxy picks the change up on its next poll instead of continuing to
         * collect a stale set.
         */
        @Modifying
        @Query("UPDATE Proxy p SET p.configRevision = p.configRevision + 1 WHERE p.id = :proxyId")
        void bumpConfigRevision(@Param("proxyId") Long proxyId);
    }

    public interface MediaTypeRepository extends JpaRepository<MediaType, Long> {
        Optional<MediaType> findByTenantIdAndName(Long tenantId, String name);

        List<MediaType> findByTenantIdOrderByName(Long tenantId);
    }

    public interface UserMediaRepository extends JpaRepository<UserMedia, Long> {
        @Query("""
                SELECT m FROM UserMedia m
                JOIN FETCH m.mediaType
                WHERE m.user.id = :userId AND m.enabled = true
                """)
        List<UserMedia> findEnabledForUser(@Param("userId") Long userId);
    }

    public interface ActionRepository extends JpaRepository<Action, Long> {
        /**
         * Enabled actions for an event source, with conditions and operations
         * fetched.
         *
         * <p>Loaded whole because every one is evaluated against every event,
         * and a lazy load per action would be an N+1 on the alerting hot path.
         */
        @Query("""
                SELECT DISTINCT a FROM Action a
                LEFT JOIN FETCH a.conditions
                WHERE a.tenantId = :tenantId
                  AND a.eventSource = :source
                  AND a.status = com.nms.server.domain.EntityStatus.ENABLED
                """)
        List<Action> findEnabledBySource(@Param("tenantId") Long tenantId,
                                         @Param("source") com.nms.server.domain.EventSource source);

        @Query("""
                SELECT DISTINCT a FROM Action a
                LEFT JOIN FETCH a.operations o
                LEFT JOIN FETCH o.users
                LEFT JOIN FETCH o.userGroups
                WHERE a.id = :actionId
                """)
        Optional<Action> findByIdWithOperations(@Param("actionId") Long actionId);

        List<Action> findByTenantIdOrderByName(Long tenantId);
    }

    public interface EscalationRepository extends JpaRepository<Escalation, Long> {
        /** Escalations whose next rung is due. */
        @Query("""
                SELECT e FROM Escalation e
                WHERE e.nextRunAt <= :now
                  AND e.status IN (com.nms.server.domain.EscalationStatus.ACTIVE,
                                   com.nms.server.domain.EscalationStatus.RECOVERING)
                ORDER BY e.nextRunAt
                """)
        List<Escalation> findDue(@Param("now") Instant now,
                                 org.springframework.data.domain.Pageable pageable);

        List<Escalation> findByProblemId(Long problemId);

        @Query("""
                SELECT e FROM Escalation e
                WHERE e.problem.id = :problemId
                  AND e.status NOT IN (com.nms.server.domain.EscalationStatus.COMPLETED,
                                       com.nms.server.domain.EscalationStatus.CANCELLED)
                """)
        List<Escalation> findActiveByProblemId(@Param("problemId") Long problemId);

        @Modifying
        @Query("UPDATE Escalation e SET e.status = :status WHERE e.problem.id = :problemId")
        void updateStatusForProblem(@Param("problemId") Long problemId,
                                    @Param("status") EscalationStatus status);
    }

    public interface AlertRepository extends JpaRepository<Alert, Long> {
        /**
         * Claims pending alerts for delivery.
         *
         * <p>{@code SKIP LOCKED} lets several server instances drain the queue
         * concurrently without any one message being sent twice.
         */
        @Query(value = """
                SELECT * FROM alert
                WHERE status = 'NEW'
                  AND scheduled_at <= :now
                ORDER BY scheduled_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, nativeQuery = true)
        List<Alert> claimPending(@Param("now") Instant now, @Param("limit") int limit);

        @Query("""
                SELECT a FROM Alert a
                LEFT JOIN FETCH a.mediaType
                WHERE a.problem.id = :problemId
                ORDER BY a.createdAt DESC
                """)
        List<Alert> findByProblemId(@Param("problemId") Long problemId);

        @Modifying
        @Query("UPDATE Alert a SET a.status = :status WHERE a.problem.id = :problemId AND a.status = com.nms.server.domain.AlertStatus.NEW")
        int cancelPendingForProblem(@Param("problemId") Long problemId,
                                    @Param("status") AlertStatus status);

        long countByStatus(AlertStatus status);
    }

    public interface MaintenanceRepository extends JpaRepository<Maintenance, Long> {
        /**
         * Maintenance windows whose outer range covers now.
         *
         * <p>The inner periods still have to be evaluated, but this narrows the
         * set to the few that could possibly apply.
         */
        @Query("""
                SELECT DISTINCT m FROM Maintenance m
                LEFT JOIN FETCH m.periods
                WHERE m.tenantId = :tenantId
                  AND m.activeSince <= :now
                  AND m.activeTill > :now
                """)
        List<Maintenance> findActiveWindows(@Param("tenantId") Long tenantId,
                                            @Param("now") Instant now);

        List<Maintenance> findByTenantIdOrderByActiveSinceDesc(Long tenantId);
    }

    public interface GlobalMacroRepository extends JpaRepository<GlobalMacro, Long> {
        List<GlobalMacro> findByTenantId(Long tenantId);

        Optional<GlobalMacro> findByTenantIdAndMacro(Long tenantId, String macro);
    }

    public interface DashboardRepository extends JpaRepository<Dashboard, Long> {
        @Query("""
                SELECT DISTINCT d FROM Dashboard d
                LEFT JOIN FETCH d.widgets
                WHERE d.id = :dashboardId
                """)
        Optional<Dashboard> findByIdWithWidgets(@Param("dashboardId") Long dashboardId);

        List<Dashboard> findByTenantIdOrderByName(Long tenantId);
    }

    public interface AvailabilitySpanRepository extends JpaRepository<AvailabilitySpan, Long> {
        /** The host's current state; exactly one span is open at a time. */
        @Query("SELECT s FROM AvailabilitySpan s WHERE s.host.id = :hostId AND s.endedAt IS NULL")
        Optional<AvailabilitySpan> findOpenForHost(@Param("hostId") Long hostId);

        @Query("""
                SELECT s FROM AvailabilitySpan s
                WHERE s.host.id = :hostId
                  AND s.startedAt < :until
                  AND (s.endedAt IS NULL OR s.endedAt > :since)
                ORDER BY s.startedAt
                """)
        List<AvailabilitySpan> findOverlapping(@Param("hostId") Long hostId,
                                               @Param("since") Instant since,
                                               @Param("until") Instant until);
    }

    public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
        org.springframework.data.domain.Page<AuditLog> findByTenantIdOrderByClockDesc(
                Long tenantId, org.springframework.data.domain.Pageable pageable);
    }
}
