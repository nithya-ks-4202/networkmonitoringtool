package com.nms.server.service;

import com.nms.server.api.dto.HostDtos.HostDetail;
import com.nms.server.api.dto.HostDtos.HostRequest;
import com.nms.server.api.dto.HostDtos.HostSummary;
import com.nms.server.api.dto.HostDtos.InterfaceRequest;
import com.nms.server.api.dto.HostDtos.InterfaceView;
import com.nms.server.domain.EntityStatus;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostClass;
import com.nms.server.domain.HostFlags;
import com.nms.server.domain.HostGroup;
import com.nms.server.domain.HostInterface;
import com.nms.server.domain.HostMacro;
import com.nms.server.domain.HostTag;
import com.nms.server.domain.InterfaceType;
import com.nms.server.domain.Proxy;
import com.nms.server.repository.CoreRepositories.HostGroupRepository;
import com.nms.server.repository.CoreRepositories.ProxyRepository;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.ProblemRepository;
import com.nms.server.repository.TriggerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates and maintains hosts.
 *
 * <p>Changing a host invalidates its proxy's cached configuration, so the proxy
 * picks the change up on its next poll rather than continuing to collect a set
 * that no longer matches what an operator just configured.
 */
@Service
public class HostService {

    private static final Logger log = LoggerFactory.getLogger(HostService.class);

    private final HostRepository hosts;
    private final HostGroupRepository groups;
    private final ProxyRepository proxies;
    private final ItemRepository items;
    private final TriggerRepository triggers;
    private final ProblemRepository problems;
    private final TemplateLinker templateLinker;

    public HostService(HostRepository hosts,
                       HostGroupRepository groups,
                       ProxyRepository proxies,
                       ItemRepository items,
                       TriggerRepository triggers,
                       ProblemRepository problems,
                       TemplateLinker templateLinker) {
        this.hosts = hosts;
        this.groups = groups;
        this.proxies = proxies;
        this.items = items;
        this.triggers = triggers;
        this.problems = problems;
        this.templateLinker = templateLinker;
    }

    /** Monitored hosts, with their open problem counts. */
    @Transactional(readOnly = true)
    public List<HostSummary> list(Long tenantId, HostClass hostClass) {
        List<Host> found = hostClass == null
                ? hosts.findMonitored(tenantId)
                : hosts.findByClass(tenantId, hostClass);

        // Counted in one query and joined in memory; asking per host would be
        // an N+1 across a list that is rendered constantly.
        Map<Long, Integer> problemCounts = openProblemCounts(found);

        return found.stream()
                .map(host -> HostSummary.from(host, problemCounts.getOrDefault(host.getId(), 0)))
                .toList();
    }

    private Map<Long, Integer> openProblemCounts(List<Host> found) {
        Map<Long, Integer> counts = new HashMap<>();
        if (found.isEmpty()) {
            return counts;
        }
        List<Long> hostIds = found.stream().map(Host::getId).toList();
        for (var problem : problems.findOpenForHosts(hostIds)) {
            if (problem.getHost() != null) {
                counts.merge(problem.getHost().getId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public Optional<HostDetail> get(Long tenantId, Long hostId) {
        return hosts.findByIdWithDetails(hostId)
                .filter(host -> host.getTenantId().equals(tenantId))
                .map(this::toDetail);
    }

    private HostDetail toDetail(Host host) {
        Map<String, String> macros = new LinkedHashMap<>();
        for (HostMacro macro : host.getMacros()) {
            // Secret macro values are never returned; the interface shows that
            // one is set without revealing it.
            macros.put(macro.getMacro(),
                    macro.getType() == com.nms.server.domain.MacroType.SECRET ? "******" : macro.getValue());
        }

        int openProblems = problems.findOpenByHostId(host.getId()).size();

        return new HostDetail(
                HostSummary.from(host, openProblems),
                host.getDescription(),
                host.getInterfaces().stream().map(InterfaceView::from).toList(),
                macros,
                host.getInventory(),
                host.getTemplates().stream().map(Host::getName).toList(),
                host.getProxy() == null ? null : host.getProxy().getId(),
                items.findByHostId(host.getId()).size(),
                triggers.findByHostId(host.getId()).size());
    }

    /** Creates a host and links the templates it asks for. */
    @Transactional
    public HostSummary create(Long tenantId, HostRequest request) {
        hosts.findByTenantIdAndTechnicalName(tenantId, request.host()).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "A host with the technical name '" + request.host() + "' already exists");
        });

        Host host = new Host();
        host.setTenantId(tenantId);
        host.setFlags(HostFlags.MONITORED);
        apply(host, request, tenantId);
        hosts.save(host);

        if (request.templates() != null && !request.templates().isEmpty()) {
            templateLinker.link(host, request.templates());
        }

        invalidateProxyConfig(host);
        log.info("Host '{}' created ({})", host.getTechnicalName(), host.getHostClass());
        return HostSummary.from(host, 0);
    }

    @Transactional
    public HostSummary update(Long tenantId, Long hostId, HostRequest request) {
        Host host = hosts.findByIdWithDetails(hostId)
                .filter(h -> h.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such host: " + hostId));

        // The proxy before the change matters as much as the one after: moving
        // a host between proxies has to invalidate both, or the old one keeps
        // polling a device that is no longer its responsibility.
        Proxy previousProxy = host.getProxy();

        apply(host, request, tenantId);
        hosts.save(host);

        if (request.templates() != null) {
            templateLinker.relink(host, request.templates());
        }

        invalidateProxyConfig(host);
        if (previousProxy != null && !previousProxy.equals(host.getProxy())) {
            proxies.bumpConfigRevision(previousProxy.getId());
        }

        return HostSummary.from(host, problems.findOpenByHostId(hostId).size());
    }

    /**
     * Replaces the set of templates linked to a host, leaving everything else
     * about it alone.
     *
     * <p>Separate from {@link #update} because that one replaces the whole
     * host: adding a template through it means echoing back the description,
     * the groups, the tags and the proxy id exactly, and anything left out is
     * cleared. Making the narrow change require the whole document is how a
     * button that links a template ends up emptying a host's groups.
     */
    @Transactional
    public HostDetail setTemplates(Long tenantId, Long hostId, List<String> templateNames) {
        Host host = hosts.findByIdWithDetails(hostId)
                .filter(h -> h.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such host: " + hostId));

        if (host.getFlags() == HostFlags.TEMPLATE) {
            // Nested templates are not supported, and silently linking one
            // template into another would copy items that then look local.
            throw new IllegalArgumentException(
                    "'" + host.getName() + "' is a template, not a host");
        }

        templateLinker.relink(host, templateNames == null ? List.of() : templateNames);
        hosts.save(host);
        invalidateProxyConfig(host);

        log.info("Host '{}' now linked to {}", host.getTechnicalName(),
                host.getTemplates().stream().map(Host::getName).toList());
        return toDetail(host);
    }

    private void apply(Host host, HostRequest request, Long tenantId) {
        host.setTechnicalName(request.host());
        host.setName(request.name() == null || request.name().isBlank()
                ? request.host() : request.name());
        host.setHostClass(request.hostClass());
        host.setDescription(request.description() == null ? "" : request.description());
        host.setStatus(request.status() == null ? EntityStatus.ENABLED : request.status());

        host.setProxy(request.proxyId() == null ? null
                : proxies.findById(request.proxyId()).orElseThrow(() ->
                        new IllegalArgumentException("No such proxy: " + request.proxyId())));

        applyGroups(host, request, tenantId);
        applyTags(host, request);
        applyMacros(host, request);
        applyInterfaces(host, request);
    }

    /**
     * Writes pending removals before their replacements are added.
     *
     * <p>The three collections below are replaced wholesale -- clear, then
     * add. Hibernate orders inserts before deletes within a single flush, so
     * on an update the new rows are written while the old ones are still
     * present and every unique constraint on those tables fires at once:
     *
     * <pre>duplicate key value violates unique constraint "uq_interface_main"</pre>
     *
     * <p>Editing a host therefore failed with a conflict every time, even when
     * the submitted values were identical to the stored ones -- so a camera's
     * address, credentials or stream path could be set once at creation and
     * never corrected. Flushing between the removal and the replacement is
     * cheaper and harder to get wrong than teaching each method to diff its
     * collection in place.
     */
    private void flushRemovals() {
        hosts.flush();
    }

    private void applyGroups(Host host, HostRequest request, Long tenantId) {
        if (request.groups() == null) {
            return;
        }
        host.getGroups().clear();
        for (String groupName : request.groups()) {
            HostGroup group = groups.findByTenantIdAndName(tenantId, groupName)
                    .orElseGet(() -> {
                        // Created on demand: refusing a host because its group
                        // does not exist yet turns a one-step operation into
                        // two for no benefit.
                        HostGroup created = new HostGroup();
                        created.setTenantId(tenantId);
                        created.setName(groupName);
                        return groups.save(created);
                    });
            host.getGroups().add(group);
        }
    }

    private void applyTags(Host host, HostRequest request) {
        if (request.tags() == null) {
            return;
        }
        host.getTags().clear();
        flushRemovals();
        for (var tagRequest : request.tags()) {
            HostTag tag = new HostTag();
            tag.setHost(host);
            tag.setTag(tagRequest.tag());
            tag.setValue(tagRequest.value() == null ? "" : tagRequest.value());
            host.getTags().add(tag);
        }
    }

    private void applyMacros(Host host, HostRequest request) {
        if (request.macros() == null) {
            return;
        }
        Map<String, HostMacro> existing = new HashMap<>();
        host.getMacros().forEach(macro -> existing.put(macro.getMacro(), macro));

        host.getMacros().clear();
        // The map above still holds the previous values in memory, so the
        // masked-secret check below works after the rows are gone.
        flushRemovals();
        request.macros().forEach((name, value) -> {
            HostMacro macro = new HostMacro();
            macro.setHost(host);
            macro.setMacro(name);
            HostMacro previous = existing.get(name);
            // A masked value means "leave it alone": the interface never
            // received the real secret, so echoing the mask back must not
            // overwrite the stored one with six asterisks.
            macro.setValue("******".equals(value) && previous != null ? previous.getValue() : value);
            // A macro keeps whatever type it was given; a new one is judged
            // from its name, so a credential is write-only from the outset
            // rather than sitting in clear until somebody notices.
            macro.setType(previous == null
                    ? com.nms.server.domain.MacroType.defaultFor(name) : previous.getType());
            host.getMacros().add(macro);
        });
    }

    private void applyInterfaces(Host host, HostRequest request) {
        if (request.interfaces() == null) {
            return;
        }

        // Kept before the clear so the credentials below survive it.
        Map<Long, HostInterface> byId = new HashMap<>();
        Map<InterfaceType, List<HostInterface>> byType = new HashMap<>();
        for (HostInterface current : host.getInterfaces()) {
            byId.put(current.getId(), current);
            byType.computeIfAbsent(current.getType(), type -> new java.util.ArrayList<>()).add(current);
        }

        host.getInterfaces().clear();
        flushRemovals();
        for (InterfaceRequest interfaceRequest : request.interfaces()) {
            host.getInterfaces().add(
                    toInterface(host, interfaceRequest, previousFor(interfaceRequest, byId, byType)));
        }
    }

    /**
     * The stored interface a submitted one is replacing, if it can be told.
     *
     * <p>Matched on the identifier when one is sent, and otherwise on the type
     * when the host has exactly one interface of it. The fallback is what makes
     * this usable rather than merely correct: replacing the collection gives
     * every interface a new identifier, so a client working from a response it
     * read a minute ago sends identifiers that no longer exist -- and matching
     * on the identifier alone would then lose the credentials it was trying to
     * preserve. Two interfaces of one type is genuinely ambiguous, so nothing
     * is assumed there.
     */
    private static HostInterface previousFor(InterfaceRequest request,
                                             Map<Long, HostInterface> byId,
                                             Map<InterfaceType, List<HostInterface>> byType) {
        if (request.id() != null) {
            HostInterface matched = byId.get(request.id());
            if (matched != null) {
                return matched;
            }
        }
        List<HostInterface> sameType = byType.getOrDefault(request.type(), List.of());
        return sameType.size() == 1 ? sameType.get(0) : null;
    }

    private HostInterface toInterface(Host host, InterfaceRequest request, HostInterface previous) {
        HostInterface hostInterface = new HostInterface();
        hostInterface.setHost(host);
        hostInterface.setType(request.type());
        hostInterface.setMain(request.main());
        hostInterface.setUseIp(request.useIp());
        hostInterface.setIp(request.ip());
        hostInterface.setDns(request.dns());
        hostInterface.setPort(request.port() > 0 ? request.port() : request.type().defaultPort());
        hostInterface.setSnmpVersion(request.snmpVersion());
        hostInterface.setSnmpCommunity(
                keptIfOmitted(request.snmpCommunity(), previous, HostInterface::getSnmpCommunity));
        hostInterface.setSnmpSecurityName(request.snmpSecurityName());
        hostInterface.setSnmpSecurityLevel(request.snmpSecurityLevel());
        hostInterface.setSnmpAuthProtocol(request.snmpAuthProtocol());
        hostInterface.setSnmpAuthPassphrase(
                keptIfOmitted(request.snmpAuthPassphrase(), previous, HostInterface::getSnmpAuthPassphrase));
        hostInterface.setSnmpPrivProtocol(request.snmpPrivProtocol());
        hostInterface.setSnmpPrivPassphrase(
                keptIfOmitted(request.snmpPrivPassphrase(), previous, HostInterface::getSnmpPrivPassphrase));
        return hostInterface;
    }

    /**
     * Keeps a stored credential that the request did not carry.
     *
     * <p>Reading a host never returns its community strings or v3 passphrases
     * -- read access to a host must not be read access to what reaches it --
     * so a client physically cannot echo them back. Replacing them with the
     * null it does send therefore destroys them on every edit: correcting a
     * switch's address through the API silently stopped its SNMP collection,
     * and the host then went unmonitored with nothing in the interface to say
     * why. The same convention the secret macros use, for the same reason: an
     * absent secret means "leave it alone", and clearing one is done by
     * sending an empty string rather than by omission.
     */
    private static String keptIfOmitted(String submitted,
                                        HostInterface previous,
                                        java.util.function.Function<HostInterface, String> stored) {
        return submitted == null && previous != null ? stored.apply(previous) : submitted;
    }

    @Transactional
    public void delete(Long tenantId, Long hostId) {
        Host host = hosts.findById(hostId)
                .filter(h -> h.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such host: " + hostId));

        // Triggers on other hosts may reference this one -- that is how a
        // switch suppresses the cameras behind it -- and deleting it silently
        // would leave them permanently unevaluatable with no explanation.
        List<com.nms.server.domain.TriggerDef> dependents = triggers.findReferencingHost(hostId).stream()
                .filter(trigger -> !trigger.getHost().getId().equals(hostId))
                .toList();
        if (!dependents.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot delete '" + host.getName() + "': " + dependents.size()
                            + " trigger(s) on other hosts reference its items. "
                            + "Remove or rewrite them first.");
        }

        Proxy proxy = host.getProxy();
        hosts.delete(host);
        if (proxy != null) {
            proxies.bumpConfigRevision(proxy.getId());
        }
        log.info("Host '{}' deleted", host.getTechnicalName());
    }

    private void invalidateProxyConfig(Host host) {
        if (host.getProxy() != null) {
            proxies.bumpConfigRevision(host.getProxy().getId());
        }
    }

    /**
     * A reference to a host, for setting a foreign key without loading it.
     *
     * <p>Used by discovery to record which host a found device became. Only
     * the id is needed for that, and fetching the whole aggregate -- its
     * interfaces, macros, groups and tags -- to write one column would be
     * several queries for nothing.
     */
    public Host reference(Long hostId) {
        return hosts.getReferenceById(hostId);
    }
}
