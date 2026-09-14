package com.nms.server.api.dto;

import com.nms.server.domain.HostClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Payloads for network discovery. */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /**
     * A rule as the operator configures it.
     *
     * <p>Expressed as "ping, these ports, SNMP yes/no" rather than as a list
     * of check objects: that is the decision being made, and the checks are
     * an implementation of it.
     */
    public record RuleRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 2048) String ipRange,
            int delaySeconds,
            Integer concurrency,
            /** Defaults to true. Some networks block ICMP, hence the switch. */
            Boolean ping,
            /** Comma-separated. Blank uses a small identifying set. */
            String tcpPorts,
            Boolean snmp,
            String snmpCommunity,
            Boolean onvif) {
    }

    public record RuleView(
            Long id,
            String name,
            String ipRange,
            /** How many addresses the range covers, so a mistake is obvious. */
            long addressCount,
            int delaySeconds,
            int concurrency,
            String status,
            Instant nextRunAt,
            boolean ping,
            String tcpPorts,
            boolean snmp,
            boolean onvif,
            /** Devices found and not yet monitored. */
            long pendingDevices) {
    }

    /** A device a sweep found, with the classifier's guess about it. */
    public record DeviceView(
            Long id,
            Long ruleId,
            String ruleName,
            String ip,
            String dns,
            String status,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Map<String, String> checkResults,
            String suggestedClass,
            String suggestedTemplate,
            /**
             * The technical name that promoting this device would use.
             *
             * <p>Computed server-side and sent rather than derived again in
             * the interface, so that adding a device through the form and
             * adding it through the API cannot produce two different names
             * for the same device.
             */
            String suggestedHost,
            String suggestedName,
            /** The evidence, so an operator can disagree on sight. */
            String reason,
            Long hostId,
            String hostName) {
    }

    /**
     * Turning a device into a monitored host.
     *
     * <p>Every field is optional: sent empty, the classifier's suggestion is
     * used. That is what makes one-click adding possible while still letting
     * someone correct a wrong guess before it becomes a host.
     */
    public record PromoteRequest(
            @Size(max = 255) String host,
            @Size(max = 255) String name,
            HostClass hostClass,
            List<String> templates,
            List<String> groups,
            Map<String, String> macros) {
    }
}
