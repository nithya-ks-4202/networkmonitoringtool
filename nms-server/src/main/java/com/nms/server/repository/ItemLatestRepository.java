package com.nms.server.repository;

import com.nms.server.domain.ItemLatest;
import com.nms.server.repository.projection.HostItemValue;
import com.nms.server.repository.projection.LatestValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ItemLatestRepository extends JpaRepository<ItemLatest, Long> {

    List<ItemLatest> findByItemIdIn(Collection<Long> itemIds);

    /** Latest values for every item on a host, with the metadata to display them. */
    @Query("""
            SELECT new com.nms.server.repository.projection.LatestValue(
                i.id, i.name, i.key, i.units, i.valueType, i.state, i.error,
                l.clock, l.valueNum, l.valueStr)
            FROM Item i
            LEFT JOIN ItemLatest l ON l.itemId = i.id
            WHERE i.host.id = :hostId
              AND i.status = com.nms.server.domain.EntityStatus.ENABLED
              AND i.flags <> com.nms.server.domain.ItemFlags.PROTOTYPE
            ORDER BY i.name
            """)
    List<LatestValue> findLatestForHost(@Param("hostId") Long hostId);

    /**
     * Latest value of one item key across every host in a group.
     *
     * <p>This is the camera wall's query: the whole grid in one round trip
     * rather than one per camera.
     */
    @Query("""
            SELECT new com.nms.server.repository.projection.HostItemValue(
                h.id, h.name, h.technicalName, i.id, l.clock, l.valueNum, l.valueStr, i.state, i.error)
            FROM Item i
            JOIN i.host h
            JOIN h.groups g
            LEFT JOIN ItemLatest l ON l.itemId = i.id
            WHERE g.name = :groupName
              AND i.key = :itemKey
              AND h.tenantId = :tenantId
              AND h.status = com.nms.server.domain.EntityStatus.ENABLED
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            ORDER BY h.name
            """)
    List<HostItemValue> findLatestByGroupAndKey(@Param("tenantId") Long tenantId,
                                                @Param("groupName") String groupName,
                                                @Param("itemKey") String itemKey);

    /**
     * Latest value of one item key across every host of a class.
     *
     * <p>Group membership is optional, so a camera that nobody filed into the
     * Cameras group still appears on the wall.
     */
    @Query("""
            SELECT new com.nms.server.repository.projection.HostItemValue(
                h.id, h.name, h.technicalName, i.id, l.clock, l.valueNum, l.valueStr, i.state, i.error)
            FROM Item i
            JOIN i.host h
            LEFT JOIN ItemLatest l ON l.itemId = i.id
            WHERE h.hostClass = :hostClass
              AND i.key = :itemKey
              AND h.tenantId = :tenantId
              AND h.status = com.nms.server.domain.EntityStatus.ENABLED
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            ORDER BY h.name
            """)
    List<HostItemValue> findLatestByHostClassAndKey(@Param("tenantId") Long tenantId,
                                                    @Param("hostClass") com.nms.server.domain.HostClass hostClass,
                                                    @Param("itemKey") String itemKey);
}
