package com.nms.server.discovery;

import com.nms.server.domain.HostClass;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Guesses what a discovered device is, and which template would monitor it.
 *
 * <p>A sweep returns open ports and a few banner strings. Turning that into
 * "this is a camera" is a guess, and it is presented as one: the suggestion
 * populates a form that a person confirms. The alternative -- adding hosts
 * automatically on the classifier's say-so -- turns every wrong guess into a
 * misconfigured host and a false alert at three in the morning.
 */
@Component
public class DeviceClassifier {

    /** Result keys the scanner writes, and this reads. */
    public static final String SNMP_DESCRIPTION = "snmp.sysDescr";
    public static final String SNMP_NAME = "snmp.sysName";
    public static final String ONVIF_INFO = "onvif.device.info";
    public static final String OPEN_PORTS = "tcp.open";

    /** RTSP. The single strongest signal that something is a camera. */
    private static final int RTSP_PORT = 554;

    private static final Set<Integer> REMOTE_ACCESS_PORTS = Set.of(22, 3389);
    private static final Set<Integer> WEB_PORTS = Set.of(80, 443, 8080, 8443);

    /**
     * Substrings that identify a camera in an SNMP description. Lower case,
     * matched case-insensitively -- vendors are not consistent about it.
     */
    private static final Set<String> CAMERA_HINTS = Set.of(
            "camera", "ipcam", "network video", "nvr", "hikvision", "dahua",
            "axis", "vivotek", "hanwha", "bosch video", "uniview");

    private static final Set<String> NETWORK_HINTS = Set.of(
            "switch", "router", "firewall", "cisco ios", "juniper", "mikrotik",
            "aruba", "fortigate", "procurve", "nx-os", "edgeswitch", "ubiquiti");

    private static final Set<String> PRINTER_HINTS = Set.of(
            "printer", "laserjet", "officejet", "kyocera", "lexmark", "ricoh");

    private static final Set<String> UPS_HINTS = Set.of("ups", "smart-ups", "eaton", "liebert");

    /**
     * A guess, and why.
     *
     * @param hostClass        what this looks like
     * @param suggestedTemplate template to link, or null if nothing fits
     * @param suggestedName    a readable name if the device offered one
     * @param reason           the evidence, shown to the operator so they can
     *                         disagree with it on sight rather than after
     *                         adding the host
     */
    public record Classification(HostClass hostClass,
                                 String suggestedTemplate,
                                 String suggestedName,
                                 String reason) {
    }

    public Classification classify(Map<String, String> checkResults) {
        Set<Integer> openPorts = parsePorts(checkResults.get(OPEN_PORTS));
        String description = lower(checkResults.get(SNMP_DESCRIPTION));
        String onvif = checkResults.get(ONVIF_INFO);
        String snmpName = checkResults.get(SNMP_NAME);

        // ONVIF is conclusive rather than suggestive: it is a camera control
        // protocol, and nothing else implements it.
        if (onvif != null && !onvif.isBlank()) {
            return new Classification(HostClass.CAMERA, "Template: IP camera",
                    firstNonBlank(onvif, snmpName),
                    "answered ONVIF, which only cameras implement");
        }

        if (openPorts.contains(RTSP_PORT)) {
            return new Classification(HostClass.CAMERA, "Template: IP camera",
                    snmpName, "RTSP (554) is open");
        }

        if (containsAny(description, CAMERA_HINTS)) {
            return new Classification(HostClass.CAMERA, "Template: IP camera",
                    snmpName, "SNMP description looks like a camera");
        }

        if (containsAny(description, PRINTER_HINTS)) {
            return new Classification(HostClass.PRINTER, null, snmpName,
                    "SNMP description looks like a printer");
        }

        if (containsAny(description, UPS_HINTS)) {
            return new Classification(HostClass.UPS, null, snmpName,
                    "SNMP description looks like a UPS");
        }

        if (containsAny(description, NETWORK_HINTS)) {
            return new Classification(HostClass.NETWORK_DEVICE, "Template: SNMP network device",
                    snmpName, "SNMP description looks like network equipment");
        }

        // Reaching here with SNMP working but no recognised vendor string:
        // still worth treating as network equipment, because on a typical
        // estate that is what answers SNMP and nothing else does.
        if (description != null && !description.isBlank()) {
            return new Classification(HostClass.NETWORK_DEVICE, "Template: SNMP network device",
                    snmpName, "answered SNMP");
        }

        if (openPorts.stream().anyMatch(REMOTE_ACCESS_PORTS::contains)) {
            // No agent template suggested: the agent has to be installed on
            // the machine first, and offering a template whose items will all
            // go unsupported until then is worse than offering none.
            return new Classification(HostClass.SERVER, "Template: ICMP reachability",
                    snmpName, "SSH or RDP is open, so this is probably a server");
        }

        if (openPorts.stream().anyMatch(WEB_PORTS::contains)) {
            return new Classification(HostClass.GENERIC, "Template: ICMP reachability",
                    snmpName, "only a web interface answered");
        }

        return new Classification(HostClass.GENERIC, "Template: ICMP reachability",
                snmpName, "answered ping only");
    }

    private static Set<Integer> parsePorts(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        java.util.Set<Integer> ports = new java.util.HashSet<>();
        for (String part : value.split(",")) {
            try {
                ports.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                // A malformed entry is not worth failing a classification
                // over; the remaining ports still say something useful.
            }
        }
        return ports;
    }

    private static boolean containsAny(String haystack, Set<String> needles) {
        if (haystack == null) {
            return false;
        }
        return needles.stream().anyMatch(haystack::contains);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
