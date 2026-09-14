package com.nms.server.api;

import com.nms.server.api.dto.DiscoveryDtos.DeviceView;
import com.nms.server.api.dto.DiscoveryDtos.PromoteRequest;
import com.nms.server.api.dto.DiscoveryDtos.RuleRequest;
import com.nms.server.api.dto.DiscoveryDtos.RuleView;
import com.nms.server.api.dto.HostDtos.HostSummary;
import com.nms.server.discovery.DiscoveryService;
import com.nms.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Finding devices on the network.
 *
 * <p>A sweep reports what is there; it never creates hosts on its own. The
 * promote endpoint is the only way a discovered device becomes monitored, and
 * it takes a deliberate call with a body the operator can correct first.
 */
@RestController
@RequestMapping("/api/discovery")
@Tag(name = "Discovery", description = "Finding devices on the network")
public class DiscoveryController {

    private final DiscoveryService discovery;

    public DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('discovery.read')")
    @Operation(summary = "List discovery rules")
    public List<RuleView> rules() {
        return discovery.listRules(AuthenticatedUser.currentTenantId());
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('discovery.write')")
    @Operation(summary = "Create a discovery rule; it runs immediately")
    public RuleView createRule(@Valid @RequestBody RuleRequest request) {
        return discovery.createRule(AuthenticatedUser.currentTenantId(), request);
    }

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('discovery.write')")
    @Operation(summary = "Delete a discovery rule and everything it found")
    public void deleteRule(@PathVariable Long ruleId) {
        discovery.deleteRule(AuthenticatedUser.currentTenantId(), ruleId);
    }

    @PostMapping("/rules/{ruleId}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('discovery.write')")
    @Operation(summary = "Run a rule now rather than at its next interval")
    public void runNow(@PathVariable Long ruleId) {
        discovery.runNow(AuthenticatedUser.currentTenantId(), ruleId);
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('discovery.read')")
    @Operation(summary = "Devices found by discovery, newest sighting first")
    public List<DeviceView> devices(
            @RequestParam(defaultValue = "false") boolean includeMonitored) {
        return discovery.listDevices(AuthenticatedUser.currentTenantId(), includeMonitored);
    }

    @PostMapping("/devices/{deviceId}/host")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('host.write')")
    @Operation(summary = "Monitor a discovered device, using the suggestion where fields are omitted")
    public HostSummary promote(@PathVariable Long deviceId,
                               @Valid @RequestBody(required = false) PromoteRequest request) {
        PromoteRequest body = request == null
                ? new PromoteRequest(null, null, null, null, null, null)
                : request;
        return discovery.promote(AuthenticatedUser.currentTenantId(), deviceId, body);
    }
}
