package com.nms.server.repository;

import com.nms.server.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("""
            SELECT e FROM Event e
            WHERE e.tenantId = :tenantId
              AND e.clock >= :since
            ORDER BY e.clock DESC
            """)
    Page<Event> findRecent(@Param("tenantId") Long tenantId,
                           @Param("since") Instant since,
                           Pageable pageable);

    @Query("""
            SELECT e FROM Event e
            WHERE e.objectType = com.nms.server.domain.EventObjectType.TRIGGER
              AND e.objectId = :triggerId
            ORDER BY e.clock DESC
            """)
    Page<Event> findByTriggerId(@Param("triggerId") Long triggerId, Pageable pageable);
}
