package com.nms.server.api;

import com.nms.server.domain.Host;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.TriggerRepository;
import com.nms.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * The templates available to link to a host.
 *
 * <p>Read-only. Templates are seeded by migration and edited as code rather
 * than through the interface, so that an estate's monitoring definition stays
 * reviewable and reproducible instead of drifting by hand on each install.
 */
@RestController
@RequestMapping("/api/templates")
@Tag(name = "Templates", description = "Monitoring templates available to link")
public class TemplateController {

    private final HostRepository hosts;
    private final ItemRepository items;
    private final TriggerRepository triggers;

    public TemplateController(HostRepository hosts, ItemRepository items, TriggerRepository triggers) {
        this.hosts = hosts;
        this.items = items;
        this.triggers = triggers;
    }

    /**
     * @param id          template host id
     * @param name        the name used to link it, exactly as it must be typed
     * @param description what it monitors
     * @param itemCount   how many items linking it will create
     * @param triggerCount how many triggers linking it will create
     */
    public record TemplateView(Long id, String name, String description,
                               String hostClass, int itemCount, int triggerCount) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('template.read')")
    @Operation(summary = "List templates that can be linked to a host")
    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        Long tenantId = AuthenticatedUser.currentTenantId();

        List<TemplateView> views = new ArrayList<>();
        for (Host template : hosts.findTemplates(tenantId)) {
            // Counted rather than listed: the form needs to show what linking
            // costs, and the full item list would be several hundred rows
            // nobody reads at that moment.
            views.add(new TemplateView(
                    template.getId(),
                    template.getName(),
                    template.getDescription(),
                    template.getHostClass().name(),
                    items.findByHostId(template.getId()).size(),
                    triggers.findByHostId(template.getId()).size()));
        }
        return views;
    }
}
