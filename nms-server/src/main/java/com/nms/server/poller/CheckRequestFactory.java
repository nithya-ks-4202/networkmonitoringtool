package com.nms.server.poller;

import com.nms.common.CheckRequest;
import com.nms.common.CheckType;
import com.nms.server.config.MacroResolver;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostInterface;
import com.nms.server.domain.InterfaceType;
import com.nms.server.domain.Item;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a configured item into a self-contained check request.
 *
 * <p>"Self-contained" is the important part. The same request is executed by
 * the server and by an on-premise proxy, and the proxy has no database: it
 * cannot look up the host, the interface or a macro. Everything the poller
 * needs -- address, port, credentials, expanded parameters -- has to be
 * resolved here and travel with the request.
 */
@Component
public class CheckRequestFactory {

    private final MacroResolver macroResolver;

    public CheckRequestFactory(MacroResolver macroResolver) {
        this.macroResolver = macroResolver;
    }

    /**
     * Builds the request for an item.
     *
     * @throws UncollectableItemException when the item cannot be polled as
     *                                    configured -- no usable interface, or
     *                                    a macro that nothing defines. Raised
     *                                    rather than returned as a value so
     *                                    the caller is forced to record the
     *                                    reason against the item.
     */
    public CheckRequest build(Item item) {
        Host host = item.getHost();
        InterfaceType requiredType = interfaceTypeFor(item.getCheckType());

        Optional<HostInterface> boundInterface = Optional.ofNullable(item.getHostInterface())
                .or(() -> host.mainInterface(requiredType))
                // Reachability checks do not care which interface they use:
                // any address on the host is a valid ping target, and
                // requiring a dedicated one would mean a camera monitored only
                // over RTSP could not be pinged.
                .or(() -> requiresAnyInterface(item.getCheckType())
                        ? host.getInterfaces().stream().filter(HostInterface::isMain).findFirst()
                        : Optional.empty())
                .or(() -> requiresAnyInterface(item.getCheckType())
                        ? host.getInterfaces().stream().findFirst()
                        : Optional.empty());

        HostInterface iface = boundInterface.orElseThrow(() -> new UncollectableItemException(
                "Host '" + host.getTechnicalName() + "' has no " + requiredType
                        + " interface for item '" + item.getKey() + "'"));

        String address = iface.connectionAddress();
        if (address == null || address.isBlank()) {
            throw new UncollectableItemException(
                    "The " + iface.getType() + " interface on host '" + host.getTechnicalName()
                            + "' has no address configured");
        }

        Map<String, String> params = new HashMap<>();
        // Interface-level SNMP credentials first, so an item may still
        // override a specific field if it genuinely needs to.
        params.putAll(iface.snmpParams());

        // The collection interval travels with the request because a proxy has
        // no item table to look it up in: without this it would have to fall
        // back to a single default for every check it runs.
        params.put("delaySeconds", Integer.toString(item.getDelaySeconds()));

        item.getParams().forEach((key, value) ->
                params.put(key, macroResolver.resolve(value, host, item.getLldMacroValues())));

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (MacroResolver.hasUnresolvedMacros(entry.getValue())) {
                throw new UncollectableItemException(
                        "Parameter '" + entry.getKey() + "' of item '" + item.getKey()
                                + "' references " + entry.getValue() + ", which no macro defines");
            }
        }

        String resolvedKey = macroResolver.resolve(item.getKey(), host, item.getLldMacroValues());

        return new CheckRequest(
                item.getId(),
                host.getId(),
                host.getTechnicalName(),
                item.getCheckType(),
                item.getValueType(),
                resolvedKey,
                address,
                iface.effectivePort(),
                Duration.ofSeconds(Math.max(1, item.getTimeoutSeconds())),
                Map.copyOf(params));
    }

    /** Which interface type a check type normally reaches the host through. */
    private static InterfaceType interfaceTypeFor(CheckType checkType) {
        return switch (checkType) {
            case SNMP, SNMP_TRAP -> InterfaceType.SNMP;
            case JMX -> InterfaceType.JMX;
            case HTTP_AGENT -> InterfaceType.HTTP;
            case RTSP -> InterfaceType.RTSP;
            case ONVIF -> InterfaceType.ONVIF;
            default -> InterfaceType.AGENT;
        };
    }

    /**
     * True for checks that need only an address, not a protocol-specific
     * interface.
     */
    private static boolean requiresAnyInterface(CheckType checkType) {
        return switch (checkType) {
            case ICMP_PING, TCP_PORT, RTSP, ONVIF, HTTP_AGENT -> true;
            default -> false;
        };
    }

    /** Raised when an item's configuration makes it impossible to collect. */
    public static class UncollectableItemException extends RuntimeException {
        public UncollectableItemException(String message) {
            super(message);
        }
    }
}
