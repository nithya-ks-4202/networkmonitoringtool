package com.nms.server.api;

import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemState;
import com.nms.common.ItemValueType;
import com.nms.common.protocol.AgentProtocol;
import com.nms.server.domain.Host;
import com.nms.server.domain.Item;
import com.nms.server.domain.Tenant;
import com.nms.server.poller.ValueProcessor;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The endpoint agents running in active mode talk to.
 *
 * <p>Active mode inverts the connection direction: the agent asks what to
 * collect and pushes values back. That is what makes a host behind NAT or a
 * one-way firewall monitorable at all, and it scales better than the server
 * holding a connection open to every machine in an estate.
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent gateway", description = "Active check distribution and value intake")
public class AgentGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayController.class);

    private final HostRepository hosts;
    private final ItemRepository items;
    private final ValueProcessor valueProcessor;

    public AgentGatewayController(HostRepository hosts,
                                  ItemRepository items,
                                  ValueProcessor valueProcessor) {
        this.hosts = hosts;
        this.items = items;
        this.valueProcessor = valueProcessor;
    }

    /**
     * Tells an agent which items it should collect.
     *
     * <p>Matched on the host's technical name, which is what the agent is
     * configured with. A 404 here means the host is not configured yet, which
     * is ordinary during a rollout rather than an error.
     */
    @PostMapping("/checks")
    @Operation(summary = "Fetch the active checks for a host")
    @Transactional(readOnly = true)
    public ResponseEntity<AgentProtocol.ActiveChecksResponse> checks(
            @RequestBody AgentProtocol.ActiveChecksRequest request) {

        Host host = hosts.findByTenantIdAndTechnicalName(Tenant.DEFAULT_ID, request.host())
                .orElse(null);
        if (host == null) {
            return ResponseEntity.notFound().build();
        }
        if (!host.isMonitored()) {
            // Known but disabled: an empty list rather than a 404, so the agent
            // stops collecting instead of retrying as though misconfigured.
            return ResponseEntity.ok(new AgentProtocol.ActiveChecksResponse(
                    "success", List.of(), "host is disabled"));
        }

        List<AgentProtocol.ActiveCheck> activeChecks = items.findByHostId(host.getId()).stream()
                .filter(item -> item.getCheckType() == CheckType.AGENT_ACTIVE)
                .filter(Item::isSchedulable)
                .map(item -> new AgentProtocol.ActiveCheck(
                        item.getId(), item.getKey(), item.getDelaySeconds(), 0L, 0))
                .toList();

        return ResponseEntity.ok(new AgentProtocol.ActiveChecksResponse(
                "success", activeChecks, activeChecks.size() + " check(s)"));
    }

    /**
     * Accepts values an agent collected.
     *
     * <p>Values are matched to items by identifier and checked against the host
     * that sent them, so an agent cannot write into another host's items by
     * guessing a number.
     */
    @PostMapping("/data")
    @Operation(summary = "Upload values collected by an agent")
    @Transactional
    public ResponseEntity<AgentProtocol.AgentDataResponse> data(
            @RequestBody AgentProtocol.AgentDataRequest request) {

        Host host = hosts.findByTenantIdAndTechnicalName(Tenant.DEFAULT_ID, request.host())
                .orElse(null);
        if (host == null) {
            return ResponseEntity.notFound().build();
        }

        Map<Long, Item> permitted = new HashMap<>();
        items.findByHostId(host.getId()).forEach(item -> permitted.put(item.getId(), item));

        int accepted = 0;
        int rejected = 0;

        for (AgentProtocol.AgentValue value : request.data() == null
                ? List.<AgentProtocol.AgentValue>of() : request.data()) {

            Item item = permitted.get(value.itemId());
            if (item == null) {
                rejected++;
                continue;
            }

            valueProcessor.accept(toCheckResult(value, item));
            accepted++;
        }

        if (rejected > 0) {
            log.warn("Agent on '{}' sent {} value(s) for items that do not belong to it",
                    request.host(), rejected);
        }

        return ResponseEntity.ok(new AgentProtocol.AgentDataResponse(
                "success", "processed " + accepted + ", rejected " + rejected));
    }

    private static CheckResult toCheckResult(AgentProtocol.AgentValue value, Item item) {
        // The agent's own clock is used, not the server's: the value describes
        // the moment it was collected, and on a batch uploaded after a network
        // outage those can be minutes apart.
        Instant clock = Instant.ofEpochSecond(value.clock(), Math.max(0, value.ns()));

        if ("NOT_SUPPORTED".equals(value.state())) {
            return new CheckResult(item.getId(), clock, ItemState.NOT_SUPPORTED, null, null,
                    value.error() == null ? "the agent does not support this key" : value.error());
        }

        ItemValueType valueType = item.getValueType();
        Object converted = value.value();

        if (valueType != null && valueType.isNumeric() && converted != null) {
            try {
                double numeric = Double.parseDouble(value.value().trim());
                converted = valueType == ItemValueType.UNSIGNED ? (Object) (long) numeric : numeric;
            } catch (NumberFormatException e) {
                return CheckResult.failed(item.getId(),
                        "agent returned '" + value.value() + "', which is not numeric");
            }
        }

        return CheckResult.ok(item.getId(), clock, converted, valueType);
    }
}
