package com.nms.server.config;

import com.nms.server.domain.GlobalMacro;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostInterface;
import com.nms.server.domain.HostMacro;
import com.nms.server.domain.InterfaceType;
import com.nms.server.repository.CoreRepositories.GlobalMacroRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {$USER.MACRO}} and {@code {HOST.*}} references.
 *
 * <p>Macros are what let one template serve a whole fleet. The camera template
 * says {@code {$CAMERA.RTSP.PORT}}; a camera whose firmware listens on 8554
 * overrides it, and nothing forks.
 *
 * <p>Resolution order is host, then linked templates, then global. Most
 * specific wins, so an override on the device beats the template's default.
 */
@Component
public class MacroResolver {

    /** {@code {$NAME}} or {@code {$NAME:"context"}}. */
    private static final Pattern USER_MACRO = Pattern.compile("\\{\\$([A-Z0-9._]+)(?::\"([^\"]*)\")?}");

    /** Built-in references such as {@code {HOST.CONN}}. */
    private static final Pattern BUILT_IN_MACRO = Pattern.compile("\\{(HOST|ITEM)\\.([A-Z0-9.]+)}");

    /** Low-level discovery macros, e.g. {@code {#IFNAME}}. */
    private static final Pattern LLD_MACRO = Pattern.compile("\\{#([A-Z0-9._]+)}");

    private final GlobalMacroRepository globalMacros;

    public MacroResolver(GlobalMacroRepository globalMacros) {
        this.globalMacros = globalMacros;
    }

    /**
     * Expands every macro reference in a string.
     *
     * <p>An unresolved macro is left verbatim rather than replaced with an
     * empty string. A poller receiving {@code {$CAMERA.RTSP.PORT}} as a port
     * fails with a message naming the macro; one receiving an empty string
     * fails with something that looks like a bug in the poller.
     *
     * @param text          the string to expand, e.g. an item parameter
     * @param host          host providing context, may be null
     * @param lldMacroValues discovery macro values, may be empty
     */
    @Transactional(readOnly = true)
    public String resolve(String text, Host host, Map<String, String> lldMacroValues) {
        if (text == null || text.isEmpty() || text.indexOf('{') < 0) {
            return text;
        }

        String result = resolveLld(text, lldMacroValues);
        result = resolveUser(result, host);
        return resolveBuiltIn(result, host);
    }

    private String resolveLld(String text, Map<String, String> lldMacroValues) {
        if (lldMacroValues == null || lldMacroValues.isEmpty()) {
            return text;
        }
        Matcher matcher = LLD_MACRO.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String full = "{#" + matcher.group(1) + "}";
            String value = lldMacroValues.get(full);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? full : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String resolveUser(String text, Host host) {
        Matcher matcher = USER_MACRO.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();

        Map<String, String> resolved = collectMacros(host);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = "{$" + matcher.group(1) + "}";
            String value = resolved.get(name);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Builds the effective macro set for a host.
     *
     * <p>Assembled lowest-precedence first so that later writes overwrite
     * earlier ones and the final map already encodes the resolution order.
     */
    private Map<String, String> collectMacros(Host host) {
        Map<String, String> resolved = new HashMap<>();

        if (host != null) {
            for (GlobalMacro macro : globalMacros.findByTenantId(host.getTenantId())) {
                resolved.put(macro.getMacro(), macro.getValue());
            }
            // Templates in link order, so a later template wins over an
            // earlier one -- matching how the interface presents them.
            for (Host template : host.getTemplates()) {
                for (HostMacro macro : template.getMacros()) {
                    resolved.put(macro.getMacro(), macro.getValue());
                }
            }
            for (HostMacro macro : host.getMacros()) {
                resolved.put(macro.getMacro(), macro.getValue());
            }
        }
        return resolved;
    }

    private String resolveBuiltIn(String text, Host host) {
        if (host == null) {
            return text;
        }
        Matcher matcher = BUILT_IN_MACRO.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = builtInValue(host, matcher.group(1), matcher.group(2));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String builtInValue(Host host, String namespace, String field) {
        if (!"HOST".equals(namespace)) {
            return null;
        }
        return switch (field) {
            case "HOST", "NAME" -> host.getName();
            case "HOST.TECHNICAL", "TECHNICAL" -> host.getTechnicalName();
            // CONN is whichever of IP or DNS the interface is configured to
            // use, which is what a poller should actually connect to.
            case "CONN" -> host.connectionAddress(InterfaceType.AGENT).orElse("");
            case "IP" -> host.mainInterface(InterfaceType.AGENT)
                    .or(() -> host.getInterfaces().stream().findFirst())
                    .map(HostInterface::getIp).orElse("");
            case "DNS" -> host.mainInterface(InterfaceType.AGENT)
                    .or(() -> host.getInterfaces().stream().findFirst())
                    .map(HostInterface::getDns).orElse("");
            case "PORT" -> host.mainInterface(InterfaceType.AGENT)
                    .map(i -> Integer.toString(i.effectivePort())).orElse("");
            case "ID" -> host.getId() == null ? "" : host.getId().toString();
            case "DESCRIPTION" -> host.getDescription();
            default -> null;
        };
    }

    /** True when the text still contains an unresolved macro reference. */
    public static boolean hasUnresolvedMacros(String text) {
        return text != null && (USER_MACRO.matcher(text).find() || LLD_MACRO.matcher(text).find());
    }
}
