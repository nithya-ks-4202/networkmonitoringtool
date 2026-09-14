package com.nms.server.repository;

import com.nms.server.domain.TriggerDef;
import com.nms.server.domain.TriggerState;
import com.nms.server.domain.TriggerValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TriggerRepository extends JpaRepository<TriggerDef, Long> {

    /**
     * Triggers that read a given item.
     *
     * <p>This is the query run for every collected value, so it resolves
     * through the {@code trigger_item} index rather than scanning expressions.
     * Dependencies and tags are fetched with it because the evaluator needs
     * both immediately and a lazy load here would be an N+1 per value.
     */
    @Query("""
            SELECT DISTINCT t FROM TriggerDef t
            LEFT JOIN FETCH t.dependencies
            JOIN t.items i
            WHERE i.id = :itemId
              AND t.status = com.nms.server.domain.EntityStatus.ENABLED
              AND t.flags <> com.nms.server.domain.TriggerFlags.PROTOTYPE
            """)
    List<TriggerDef> findEnabledByItemId(@Param("itemId") Long itemId);

    @Query("SELECT t FROM TriggerDef t WHERE t.host.id = :hostId ORDER BY t.description")
    List<TriggerDef> findByHostId(@Param("hostId") Long hostId);

    @Query("""
            SELECT DISTINCT t FROM TriggerDef t
            LEFT JOIN FETCH t.items
            LEFT JOIN FETCH t.tags
            WHERE t.id = :triggerId
            """)
    java.util.Optional<TriggerDef> findByIdWithDetails(@Param("triggerId") Long triggerId);

    /**
     * Records a trigger's new value.
     *
     * <p>Written as a targeted update because trigger state changes on every
     * evaluation that flips, and the entity carries collections that would
     * otherwise be dirty-checked each time.
     */
    @Modifying
    @Query("""
            UPDATE TriggerDef t
            SET t.value = :value, t.state = :state, t.error = :error, t.lastChangeAt = :changedAt
            WHERE t.id = :triggerId
            """)
    void updateValue(@Param("triggerId") Long triggerId,
                     @Param("value") TriggerValue value,
                     @Param("state") TriggerState state,
                     @Param("error") String error,
                     @Param("changedAt") Instant changedAt);

    @Query("""
            SELECT count(t) FROM TriggerDef t
            WHERE t.tenantId = :tenantId
              AND t.status = com.nms.server.domain.EntityStatus.ENABLED
            """)
    long countEnabled(@Param("tenantId") Long tenantId);

    /**
     * Triggers whose expression references a host, used to detect the ones
     * broken by deleting it.
     */
    @Query("""
            SELECT DISTINCT t FROM TriggerDef t
            JOIN t.items i
            WHERE i.host.id = :hostId
            """)
    List<TriggerDef> findReferencingHost(@Param("hostId") Long hostId);
}
