package com.nms.server.discovery;

import com.nms.common.CheckType;
import com.nms.server.api.dto.DiscoveryDtos.DeviceView;
import com.nms.server.api.dto.DiscoveryDtos.PromoteRequest;
import com.nms.server.api.dto.DiscoveryDtos.RuleRequest;
import com.nms.server.api.dto.DiscoveryDtos.RuleView;
import com.nms.server.api.dto.HostDtos.HostRequest;
import com.nms.server.api.dto.HostDtos.HostSummary;
import com.nms.server.api.dto.HostDtos.InterfaceRequest;
import com.nms.server.domain.DiscoveredHost;
import com.nms.server.domain.DiscoveryCheck;
import com.nms.server.domain.DiscoveryRule;
import com.nms.server.domain.EntityStatus;
import com.nms.server.domain.HostClass;
import com.nms.server.domain.InterfaceType;
import com.nms.server.repository.DiscoveryRepositories.DiscoveredHostRepository;
import com.nms.server.repository.DiscoveryRepositories.DiscoveryRuleRepository;
import com.nms.server.service.HostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovery rules, their findings, and turning a finding into a host. */
@Service
public class DiscoveryService {

    private final DiscoveryRuleRepository rules;
    private final DiscoveredHostRepository discovered;
    private final DeviceClassifier classifier;
    private final HostService hosts;

    public DiscoveryService(DiscoveryRuleRepository rules,
                            DiscoveredHostRepository discovered,
                            DeviceClassifier classifier,
                            HostService hosts) {
        this.rules = rules;
        this.discovered = discovered;
        this.classifier = classifier;
        this.hosts = hosts;
    }

    @Transactional(readOnly = true)
    public List<RuleView> listRules(Long tenantId) {
        List<RuleView> views = new ArrayList<>();
        for (DiscoveryRule rule : rules.findByTenantIdOrderByName(tenantId)) {
            views.add(toView(rule));
        }
        return views;
    }

    @Transactional
    public RuleView createRule(Long tenantId, RuleRequest request) {
        // Validated here rather than left to the scanner, so an impossible
        // range is rejected while the operator is still looking at the form
        // instead of an hour later in a log they are not reading.
        IpRange range = IpRange.parse(request.ipRange());

        rules.findByTenantIdAndName(tenantId, request.name()).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "A discovery rule named '" + request.name() + "' already exists");
        });

        DiscoveryRule rule = new DiscoveryRule();
        rule.setTenantId(tenantId);
        rule.setName(request.name());
        rule.setIpRange(request.ipRange());
        rule.setDelaySeconds(request.delaySeconds() > 0 ? request.delaySeconds() : 3600);
        rule.setConcurrency(clampConcurrency(request.concurrency()));
        rule.setStatus(EntityStatus.ENABLED);
        // Due immediately: someone who has just created a rule wants to see
        // it find something, not wait an hour to learn they mistyped the
        // range.
        rule.setNextRunAt(Instant.now());

        rule.getChecks().addAll(buildChecks(rule, request));

        if (rule.getChecks().isEmpty()) {
            throw new IllegalArgumentException(
                    "A rule needs at least one check, or it can find nothing");
        }

        DiscoveryRule saved = rules.save(rule);
        return toView(saved, range.size());
    }

    /**
     * Builds the probes for a rule.
     *
     * <p>The API takes "ping, these TCP ports, SNMP yes/no" rather than a
     * list of check objects, because that is the decision an operator is
     * actually making. The checks are an implementation of it.
     */
    private List<DiscoveryCheck> buildChecks(DiscoveryRule rule, RuleRequest request) {
        List<DiscoveryCheck> checks = new ArrayList<>();

        if (request.ping() == null || request.ping()) {
            checks.add(check(rule, CheckType.ICMP_PING, ""));
        }

        String ports = request.tcpPorts() == null || request.tcpPorts().isBlank()
                // Chosen to identify rather than to enumerate: RTSP and ONVIF
                // for cameras, SSH/RDP for servers, HTTP for everything with
                // a web interface. Deliberately not a wide sweep.
                ? "80,443,554,22,3389,8000,8080"
                : request.tcpPorts();
        checks.add(check(rule, CheckType.TCP_PORT, ports));

        if (request.snmp() != null && request.snmp()) {
            DiscoveryCheck snmp = check(rule, CheckType.SNMP, "161");
            if (request.snmpCommunity() != null && !request.snmpCommunity().isBlank()) {
                snmp.getParams().put("community", request.snmpCommunity());
            }
            snmp.setNameSource(true);
            checks.add(snmp);
        }

        if (request.onvif() != null && request.onvif()) {
            checks.add(check(rule, CheckType.ONVIF, "80"));
        }

        return checks;
    }

    private static DiscoveryCheck check(DiscoveryRule rule, CheckType type, String ports) {
        DiscoveryCheck check = new DiscoveryCheck();
        check.setRule(rule);
        check.setCheckType(type);
        check.setPorts(ports);
        return check;
    }

    @Transactional
    public void deleteRule(Long tenantId, Long ruleId) {
        DiscoveryRule rule = rules.findByIdAndTenantId(ruleId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such discovery rule"));
        rules.delete(rule);
    }

    /** Makes a rule due now, without waiting for its interval. */
    @Transactional
    public void runNow(Long tenantId, Long ruleId) {
        DiscoveryRule rule = rules.findByIdAndTenantId(ruleId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such discovery rule"));
        rules.reschedule(rule.getId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<DeviceView> listDevices(Long tenantId, boolean includeMonitored) {
        List<DeviceView> views = new ArrayList<>();
        for (DiscoveredHost device : discovered.findForTenant(tenantId, includeMonitored)) {
            DeviceClassifier.Classification guess = classifier.classify(device.getCheckResults());
            views.add(new DeviceView(
                    device.getId(),
                    device.getRule().getId(),
                    device.getRule().getName(),
                    device.getIp(),
                    device.getDns(),
                    device.getStatus().name(),
                    device.getFirstSeenAt(),
                    device.getLastSeenAt(),
                    device.getCheckResults(),
                    guess.hostClass().name(),
                    guess.suggestedTemplate(),
                    defaultTechnicalName(device, guess),
                    suggestedName(device, guess),
                    guess.reason(),
                    device.getHost() == null ? null : device.getHost().getId(),
                    device.getHost() == null ? null : device.getHost().getTechnicalName()));
        }
        return views;
    }

    /**
     * Turns a sighting into a monitored host.
     *
     * <p>The link back is recorded so the same device is not offered again on
     * the next sweep -- a discovery list that keeps re-suggesting things you
     * already monitor stops being read.
     */
    @Transactional
    public HostSummary promote(Long tenantId, Long deviceId, PromoteRequest request) {
        DiscoveredHost device = discovered.findByIdAndRuleTenantId(deviceId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No such discovered device"));

        if (device.getHost() != null) {
            throw new IllegalArgumentException(
                    "This device is already monitored as '" + device.getHost().getTechnicalName() + "'");
        }

        DeviceClassifier.Classification guess = classifier.classify(device.getCheckResults());

        String technicalName = blankToNull(request.host()) != null
                ? request.host()
                : defaultTechnicalName(device, guess);

        HostClass hostClass = request.hostClass() != null
                ? request.hostClass()
                : guess.hostClass();

        List<String> templates = request.templates() != null && !request.templates().isEmpty()
                ? request.templates()
                : (guess.suggestedTemplate() == null ? List.of() : List.of(guess.suggestedTemplate()));

        HostRequest hostRequest = new HostRequest(
                technicalName,
                blankToNull(request.name()) != null ? request.name() : suggestedName(device, guess),
                hostClass,
                "Added from network discovery: " + guess.reason(),
                EntityStatus.ENABLED,
                request.groups() != null && !request.groups().isEmpty()
                        ? request.groups()
                        : List.of("Discovered hosts"),
                List.of(),
                request.macros() == null ? Map.of() : request.macros(),
                List.of(new InterfaceRequest(null, InterfaceType.AGENT, true, true,
                        device.getIp(), null, interfacePort(hostClass),
                        null, null, null, null, null, null, null, null)),
                templates,
                null);

        HostSummary created = hosts.create(tenantId, hostRequest);

        device.setHost(hosts.reference(created.id()));
        discovered.save(device);

        return created;
    }

    /**
     * The port on the host's single interface.
     *
     * <p>It is only the fallback address holder: the camera template reads
     * its RTSP and ONVIF ports from macros, so this needs to be plausible
     * rather than exact.
     */
    private static int interfacePort(HostClass hostClass) {
        return hostClass == HostClass.CAMERA ? 80 : 10150;
    }

    private static String defaultTechnicalName(DiscoveredHost device,
                                               DeviceClassifier.Classification guess) {
        String name = guess.suggestedName();
        if (name != null && !name.isBlank()) {
            // Technical names are used in trigger expressions, so anything
            // that would need escaping there is replaced rather than left to
            // break an expression later.
            String cleaned = name.trim().replaceAll("[^A-Za-z0-9._-]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (!cleaned.isEmpty()) {
                return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
            }
        }
        return prefixFor(guess.hostClass()) + device.getIp().replace('.', '-');
    }

    private static String prefixFor(HostClass hostClass) {
        return switch (hostClass) {
            case CAMERA -> "cam-";
            case NETWORK_DEVICE -> "net-";
            case PRINTER -> "prn-";
            case UPS -> "ups-";
            case SERVER -> "srv-";
            default -> "dev-";
        };
    }

    private static String suggestedName(DiscoveredHost device,
                                        DeviceClassifier.Classification guess) {
        if (guess.suggestedName() != null && !guess.suggestedName().isBlank()) {
            return guess.suggestedName().trim();
        }
        if (!device.getDns().isBlank()) {
            return device.getDns();
        }
        return device.getIp();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Kept low. A sweep is unsolicited traffic to equipment that did not ask
     * for it, and a high enough rate is indistinguishable from a port scan.
     */
    private static int clampConcurrency(Integer requested) {
        if (requested == null || requested <= 0) {
            return 32;
        }
        return Math.min(requested, 128);
    }

    private RuleView toView(DiscoveryRule rule) {
        long size;
        try {
            size = IpRange.parse(rule.getIpRange()).size();
        } catch (IllegalArgumentException e) {
            size = 0;
        }
        return toView(rule, size);
    }

    private RuleView toView(DiscoveryRule rule, long addressCount) {
        Map<String, Boolean> probes = new LinkedHashMap<>();
        StringBuilder ports = new StringBuilder();
        for (DiscoveryCheck check : rule.getChecks()) {
            probes.put(check.getCheckType().name(), true);
            if (check.getCheckType() == CheckType.TCP_PORT) {
                ports.append(check.getPorts());
            }
        }

        return new RuleView(
                rule.getId(),
                rule.getName(),
                rule.getIpRange(),
                addressCount,
                rule.getDelaySeconds(),
                rule.getConcurrency(),
                rule.getStatus().name(),
                rule.getNextRunAt(),
                probes.containsKey(CheckType.ICMP_PING.name()),
                ports.toString(),
                probes.containsKey(CheckType.SNMP.name()),
                probes.containsKey(CheckType.ONVIF.name()),
                discovered.countByRuleIdAndHostIsNull(rule.getId()));
    }
}
