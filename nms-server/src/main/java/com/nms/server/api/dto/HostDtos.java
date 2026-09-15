package com.nms.server.api.dto;

import com.nms.server.domain.Availability;
import com.nms.server.domain.EntityStatus;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostClass;
import com.nms.server.domain.HostInterface;
import com.nms.server.domain.InterfaceType;
import com.nms.server.domain.MaintenanceStatus;
import com.nms.server.domain.SnmpVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for host configuration.
 *
 * <p>Separate from the entities on purpose. Entities carry lazy collections, a
 * persistence identity and credentials; serialising them directly would leak
 * SNMP community strings and camera passwords to anyone who can read a host,
 * and would couple the public API to the schema.
 */
public final class HostDtos {

    private HostDtos() {
    }

    /** A host as the interface lists it. */
    public record HostSummary(
            Long id,
            String host,
            String name,
            HostClass hostClass,
            EntityStatus status,
            MaintenanceStatus maintenanceStatus,
            String address,
            List<String> groups,
            List<String> tags,
            String proxyName,
            Availability availability,
            int openProblems,
            Instant updatedAt) {

        public static HostSummary from(Host host, int openProblems) {
            return new HostSummary(
                    host.getId(),
                    host.getTechnicalName(),
                    host.getName(),
                    host.getHostClass(),
                    host.getStatus(),
                    host.getMaintenanceStatus(),
                    host.connectionAddress(InterfaceType.AGENT).orElse(""),
                    host.getGroups().stream().map(com.nms.server.domain.HostGroup::getName).toList(),
                    host.getTags().stream()
                            .map(tag -> tag.getValue().isEmpty()
                                    ? tag.getTag() : tag.getTag() + ": " + tag.getValue())
                            .toList(),
                    host.getProxy() == null ? null : host.getProxy().getName(),
                    overallAvailability(host),
                    openProblems,
                    host.getUpdatedAt());
        }

        /**
         * The worst state across the host's interfaces.
         *
         * <p>Deliberately pessimistic: a camera answering ICMP while its RTSP
         * interface is unavailable is not a healthy camera, and showing it as
         * available would hide exactly the failure the operator cares about.
         */
        private static Availability overallAvailability(Host host) {
            if (host.getInterfaces().stream()
                    .anyMatch(i -> i.getAvailable() == Availability.UNAVAILABLE)) {
                return Availability.UNAVAILABLE;
            }
            if (host.getInterfaces().stream()
                    .anyMatch(i -> i.getAvailable() == Availability.AVAILABLE)) {
                return Availability.AVAILABLE;
            }
            return Availability.UNKNOWN;
        }
    }

    /** A host with everything the detail view shows. */
    public record HostDetail(
            HostSummary summary,
            String description,
            List<InterfaceView> interfaces,
            Map<String, String> macros,
            Map<String, String> inventory,
            List<String> templates,
            /**
             * The proxy's identifier as well as the name the summary carries.
             * Without it a client cannot send back what it was given: the
             * full-replace endpoint takes an id, and an absent one unassigns
             * the proxy -- so editing a remote site's host through the API
             * moved it back to being polled from the centre, across a link
             * the proxy exists to avoid using.
             */
            Long proxyId,
            int itemCount,
            int triggerCount) {
    }

    /** One interface, with credentials omitted. */
    public record InterfaceView(
            Long id,
            InterfaceType type,
            boolean main,
            boolean useIp,
            String ip,
            String dns,
            int port,
            SnmpVersion snmpVersion,
            Availability available,
            String error) {

        public static InterfaceView from(HostInterface hostInterface) {
            return new InterfaceView(
                    hostInterface.getId(),
                    hostInterface.getType(),
                    hostInterface.isMain(),
                    hostInterface.isUseIp(),
                    hostInterface.getIp(),
                    hostInterface.getDns(),
                    hostInterface.effectivePort(),
                    hostInterface.getSnmpVersion(),
                    hostInterface.getAvailable(),
                    hostInterface.getError());
            // Community strings and v3 passphrases are intentionally absent:
            // read access to a host must not be read access to the credentials
            // that reach it.
        }
    }

    /** Payload for creating or updating a host. */
    public record HostRequest(
            @NotBlank @Size(max = 255) String host,
            @Size(max = 255) String name,
            @NotNull HostClass hostClass,
            String description,
            EntityStatus status,
            List<String> groups,
            List<TagRequest> tags,
            Map<String, String> macros,
            @Valid List<InterfaceRequest> interfaces,
            List<String> templates,
            Long proxyId) {
    }

    /** An interface being configured, including its credentials. */
    public record InterfaceRequest(
            Long id,
            @NotNull InterfaceType type,
            boolean main,
            boolean useIp,
            String ip,
            String dns,
            int port,
            SnmpVersion snmpVersion,
            String snmpCommunity,
            String snmpSecurityName,
            String snmpSecurityLevel,
            String snmpAuthProtocol,
            String snmpAuthPassphrase,
            String snmpPrivProtocol,
            String snmpPrivPassphrase) {
    }

    /** A tag being set on a host. */
    public record TagRequest(@NotBlank String tag, String value) {
    }

    /**
     * The templates a host should be linked to.
     *
     * <p>The complete set, not an addition: a name absent from the list is
     * unlinked, which removes the items it created along with their history.
     */
    public record TemplateLinkRequest(@NotNull List<String> templates) {
    }
}
