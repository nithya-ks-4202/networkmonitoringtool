package com.nms.server.repository;

import com.nms.server.domain.Item;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {

    /**
     * Claims the next batch of due items for polling.
     *
     * <p>{@code SKIP LOCKED} is what allows several server instances to share
     * one queue: each takes rows the others have not locked, with no
     * coordination and no risk of the same item being polled twice. Without it
     * the instances would either serialise on the same rows or duplicate work.
     *
     * <p>Templates and prototypes are excluded at the query rather than
     * filtered afterwards, so a large template library costs nothing here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
            SELECT i.* FROM item i
            JOIN host h ON h.host_id = i.host_id
            WHERE i.status = 'ENABLED'
              AND i.flags IN ('NORMAL', 'DISCOVERED', 'DISCOVERY_RULE')
              AND h.status = 'ENABLED'
              AND h.flags = 'MONITORED'
              AND (i.next_check_at IS NULL OR i.next_check_at <= :now)
              AND (:proxyId IS NULL AND h.proxy_id IS NULL
                   OR h.proxy_id = :proxyId)
            ORDER BY i.next_check_at NULLS FIRST
            LIMIT :limit
            FOR UPDATE OF i SKIP LOCKED
            """, nativeQuery = true)
    List<Item> claimDueItems(@Param("now") Instant now,
                             @Param("proxyId") Long proxyId,
                             @Param("limit") int limit);

    /** Items a given proxy is responsible for, used to build its configuration. */
    @Query("""
            SELECT i FROM Item i
            JOIN FETCH i.host h
            LEFT JOIN FETCH i.hostInterface
            WHERE h.proxy.id = :proxyId
              AND i.status = 'ENABLED'
              AND h.status = 'ENABLED'
              AND h.flags = 'MONITORED'
              AND i.flags IN (com.nms.server.domain.ItemFlags.NORMAL,
                              com.nms.server.domain.ItemFlags.DISCOVERED,
                              com.nms.server.domain.ItemFlags.DISCOVERY_RULE)
            """)
    List<Item> findForProxy(@Param("proxyId") Long proxyId);

    List<Item> findByHostId(Long hostId);

    Optional<Item> findByHostIdAndKey(Long hostId, String key);

    /**
     * Resolves a trigger expression's {@code /host/key} reference.
     *
     * <p>Matched on the technical host name, which is what expressions use, so
     * renaming a host's visible name never breaks a trigger.
     */
    @Query("""
            SELECT i FROM Item i
            WHERE i.host.technicalName = :hostName
              AND i.key = :key
              AND i.tenantId = :tenantId
            """)
    Optional<Item> findByHostNameAndKey(@Param("tenantId") Long tenantId,
                                        @Param("hostName") String hostName,
                                        @Param("key") String key);

    @Query("SELECT i FROM Item i WHERE i.tenantId = :tenantId AND i.flags = com.nms.server.domain.ItemFlags.PROTOTYPE AND i.host.id = :hostId")
    List<Item> findPrototypesForHost(@Param("tenantId") Long tenantId, @Param("hostId") Long hostId);

    /**
     * Moves an item's next check forward without loading it.
     *
     * <p>The scheduler does this for every polled item, so it is kept to a
     * single indexed UPDATE rather than a load-modify-flush cycle.
     */
    @Modifying
    @Query("""
            UPDATE Item i
            SET i.nextCheckAt = :nextCheckAt, i.lastCheckAt = :lastCheckAt
            WHERE i.id = :itemId
            """)
    void reschedule(@Param("itemId") Long itemId,
                    @Param("nextCheckAt") Instant nextCheckAt,
                    @Param("lastCheckAt") Instant lastCheckAt);

    @Modifying
    @Query("UPDATE Item i SET i.state = :state, i.error = :error WHERE i.id = :itemId")
    void updateState(@Param("itemId") Long itemId,
                     @Param("state") com.nms.common.ItemState state,
                     @Param("error") String error);

    @Query("SELECT count(i) FROM Item i WHERE i.tenantId = :tenantId AND i.status = com.nms.server.domain.EntityStatus.ENABLED")
    long countEnabled(@Param("tenantId") Long tenantId);

    @Query("SELECT count(i) FROM Item i WHERE i.tenantId = :tenantId AND i.state = com.nms.common.ItemState.NOT_SUPPORTED")
    long countUnsupported(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT i FROM Item i
            WHERE i.tenantId = :tenantId
              AND i.state = com.nms.common.ItemState.NOT_SUPPORTED
            ORDER BY i.lastCheckAt DESC
            """)
    List<Item> findUnsupported(@Param("tenantId") Long tenantId, Pageable pageable);
}
