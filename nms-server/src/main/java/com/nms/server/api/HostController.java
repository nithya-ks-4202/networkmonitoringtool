package com.nms.server.api;

import com.nms.server.api.dto.HostDtos.HostDetail;
import com.nms.server.api.dto.HostDtos.HostRequest;
import com.nms.server.api.dto.HostDtos.HostSummary;
import com.nms.server.domain.HostClass;
import com.nms.server.security.AuthenticatedUser;
import com.nms.server.service.HostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/hosts")
@Tag(name = "Hosts", description = "Monitored device configuration")
public class HostController {

    private final HostService hostService;

    public HostController(HostService hostService) {
        this.hostService = hostService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('host.read')")
    @Operation(summary = "List monitored hosts, optionally filtered by class")
    public List<HostSummary> list(@RequestParam(required = false) HostClass hostClass) {
        return hostService.list(AuthenticatedUser.currentTenantId(), hostClass);
    }

    @GetMapping("/{hostId}")
    @PreAuthorize("hasAuthority('host.read')")
    @Operation(summary = "Fetch one host with its interfaces, macros and inventory")
    public ResponseEntity<HostDetail> get(@PathVariable Long hostId) {
        return hostService.get(AuthenticatedUser.currentTenantId(), hostId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('host.write')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a host and link any templates it names")
    public HostSummary create(@Valid @RequestBody HostRequest request) {
        return hostService.create(AuthenticatedUser.currentTenantId(), request);
    }

    @PutMapping("/{hostId}")
    @PreAuthorize("hasAuthority('host.write')")
    @Operation(summary = "Replace a host's configuration")
    public HostSummary update(@PathVariable Long hostId, @Valid @RequestBody HostRequest request) {
        return hostService.update(AuthenticatedUser.currentTenantId(), hostId, request);
    }

    @DeleteMapping("/{hostId}")
    @PreAuthorize("hasAuthority('host.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a host, its items and its collected history")
    public void delete(@PathVariable Long hostId) {
        hostService.delete(AuthenticatedUser.currentTenantId(), hostId);
    }
}
