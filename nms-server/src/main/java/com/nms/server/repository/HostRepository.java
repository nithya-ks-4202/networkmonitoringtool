package com.nms.server.repository;

import com.nms.server.domain.Host;
import com.nms.server.domain.HostClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HostRepository extends JpaRepository<Host, Long>, JpaSpecificationExecutor<Host> {

    Optional<Host> findByTenantIdAndTechnicalName(Long tenantId, String technicalName);

    @Query("""
            SELECT h FROM Host h
            WHERE h.tenantId = :tenantId
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            ORDER BY h.name
            """)
    List<Host> findMonitored(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT h FROM Host h
            WHERE h.tenantId = :tenantId
              AND h.flags = com.nms.server.domain.HostFlags.TEMPLATE
            ORDER BY h.name
            """)
    List<Host> findTemplates(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT h FROM Host h
            WHERE h.tenantId = :tenantId
              AND h.hostClass = :hostClass
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            ORDER BY h.name
            """)
    List<Host> findByClass(@Param("tenantId") Long tenantId, @Param("hostClass") HostClass hostClass);

    /**
     * Hosts in a named group, loading interfaces eagerly.
     *
     * <p>Used by views that render an address per host -- the camera wall, the
     * host list -- where a lazy load would mean one query per row.
     */
    @Query("""
            SELECT DISTINCT h FROM Host h
            LEFT JOIN FETCH h.interfaces
            JOIN h.groups g
            WHERE h.tenantId = :tenantId
              AND g.name = :groupName
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            ORDER BY h.name
            """)
    List<Host> findByGroupNameWithInterfaces(@Param("tenantId") Long tenantId,
                                             @Param("groupName") String groupName);

    @Query("SELECT h FROM Host h WHERE h.proxy.id = :proxyId")
    List<Host> findByProxyId(@Param("proxyId") Long proxyId);

    @Query("""
            SELECT count(h) FROM Host h
            WHERE h.tenantId = :tenantId
              AND h.flags = com.nms.server.domain.HostFlags.MONITORED
            """)
    long countMonitored(@Param("tenantId") Long tenantId);

    /** Loads a host with everything the configuration API needs in one query. */
    @Query("""
            SELECT h FROM Host h
            LEFT JOIN FETCH h.interfaces
            LEFT JOIN FETCH h.macros
            LEFT JOIN FETCH h.tags
            WHERE h.id = :hostId
            """)
    Optional<Host> findByIdWithDetails(@Param("hostId") Long hostId);
}
