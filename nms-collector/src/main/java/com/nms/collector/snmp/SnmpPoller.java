package com.nms.collector.snmp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Target;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads values from SNMP agents.
 *
 * <p>Handles two shapes of request. A plain item names one OID and gets one
 * value back. A low-level discovery rule walks a subtree and returns a JSON
 * array of entities, one per row, with the OID index and the column value
 * bound to LLD macros -- which is how per-interface items get created for a
 * switch without anyone enumerating its ports by hand.
 */
@Component
public class SnmpPoller implements Poller {

    private static final Logger log = LoggerFactory.getLogger(SnmpPoller.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Guards against a mis-scoped walk pulling an entire MIB into memory. */
    private static final int MAX_WALK_ROWS = 10_000;

    private final SnmpSession session;

    public SnmpPoller(SnmpSession session) {
        this.session = session;
    }

    @Override
    public CheckType checkType() {
        return CheckType.SNMP;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        String oidText = request.param("oid", "");
        if (oidText.isEmpty()) {
            return CheckResult.failed(request.itemId(),
                    "SNMP item '" + request.key() + "' has no OID configured");
        }

        OID oid;
        try {
            oid = new OID(oidText.startsWith(".") ? oidText.substring(1) : oidText);
        } catch (RuntimeException e) {
            return CheckResult.failed(request.itemId(), "Malformed OID '" + oidText + "'");
        }

        Target<?> target;
        try {
            target = session.targetFor(request);
        } catch (IllegalArgumentException e) {
            return CheckResult.failed(request.itemId(), e.getMessage());
        }

        boolean walk = "true".equalsIgnoreCase(request.param("walk", "false"));
        try {
            return walk ? walk(request, target, oid) : get(request, target, oid);
        } catch (IOException e) {
            return CheckResult.failed(request.itemId(),
                    "SNMP request to " + request.address() + " failed: " + com.nms.collector.Failures.describe(e));
        }
    }

    private CheckResult get(CheckRequest request, Target<?> target, OID oid) throws IOException {
        PDU pdu = target.getVersion() == SnmpConstants.version3 ? new ScopedPDU() : new PDU();
        pdu.setType(PDU.GET);
        pdu.add(new VariableBinding(oid));

        ResponseEvent<?> event = session.snmp().send(pdu, target);
        PDU response = event.getResponse();

        if (response == null) {
            // No reply within timeout after the configured retries. On UDP this
            // is indistinguishable from a dropped packet, so it is reported as
            // a timeout rather than as "device down" -- the ICMP item is what
            // answers that question.
            return CheckResult.failed(request.itemId(),
                    "no SNMP response from " + request.address() + " within " + request.timeout());
        }
        if (response.getErrorStatus() != PDU.noError) {
            return CheckResult.failed(request.itemId(),
                    "SNMP error: " + response.getErrorStatusText()
                            + " (index " + response.getErrorIndex() + ")");
        }

        VariableBinding binding = response.get(0);
        if (binding == null || binding.getVariable() == null) {
            return CheckResult.failed(request.itemId(), "empty SNMP response");
        }
        // noSuchObject, noSuchInstance and endOfMibView all arrive as Null
        // subtypes distinguished by syntax; isExceptionSyntax covers all three.
        if (binding.getVariable() instanceof Null
                || Null.isExceptionSyntax(binding.getVariable().getSyntax())) {
            // The device does not implement this OID. Worth surfacing plainly:
            // it means the template does not match the hardware.
            return CheckResult.failed(request.itemId(),
                    "OID " + oid + " is not implemented by " + request.address());
        }

        Object value = convert(binding, request.valueType());
        if (value == null) {
            return CheckResult.failed(request.itemId(),
                    "SNMP value '" + binding.getVariable() + "' is not compatible with value type "
                            + request.valueType());
        }
        return CheckResult.ok(request.itemId(), value, request.valueType());
    }

    /**
     * Walks a subtree and renders it as LLD entities.
     *
     * <p>Each row becomes one object whose macros come from {@code lldMacros}:
     * a mapping of macro name to either {@code value} (the column's value) or
     * {@code index} (the row's OID suffix). The suffix is what later lets a
     * prototype build {@code .1.3.6.1.2.1.2.2.1.10.{#IFINDEX}} for each port.
     */
    private CheckResult walk(CheckRequest request, Target<?> target, OID rootOid) throws IOException {
        TreeUtils treeUtils = new TreeUtils(session.snmp(), new DefaultPDUFactory());
        treeUtils.setMaxRepetitions(Math.max(1, request.intParam("maxRepetitions", 10)));

        List<TreeEvent> events = treeUtils.getSubtree(target, rootOid);
        if (events == null || events.isEmpty()) {
            return CheckResult.failed(request.itemId(), "no SNMP response while walking " + rootOid);
        }

        Map<String, String> macroSources = parseMacroSources(request.param("lldMacros", ""));
        List<Map<String, String>> rows = new ArrayList<>();

        for (TreeEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.isError()) {
                return CheckResult.failed(request.itemId(),
                        "SNMP walk of " + rootOid + " failed: " + event.getErrorMessage());
            }
            VariableBinding[] bindings = event.getVariableBindings();
            if (bindings == null) {
                continue;
            }
            for (VariableBinding binding : bindings) {
                if (binding == null || binding.getVariable() instanceof Null) {
                    continue;
                }
                if (rows.size() >= MAX_WALK_ROWS) {
                    log.warn("SNMP walk of {} on {} exceeded {} rows; truncating",
                            rootOid, request.address(), MAX_WALK_ROWS);
                    break;
                }

                // The row index is whatever the returned OID has beyond the
                // root we asked for.
                OID returned = binding.getOid();
                String index = returned.size() > rootOid.size()
                        ? new OID(returned.getValue(), rootOid.size(), returned.size() - rootOid.size()).toString()
                        : "";
                String value = binding.getVariable().toString();

                Map<String, String> row = new LinkedHashMap<>();
                if (macroSources.isEmpty()) {
                    row.put("{#SNMPINDEX}", index);
                    row.put("{#SNMPVALUE}", value);
                } else {
                    row.put("{#SNMPINDEX}", index);
                    macroSources.forEach((macro, source) ->
                            row.put(macro, "index".equals(source) ? index : value));
                }
                rows.add(row);
            }
        }

        try {
            return CheckResult.ok(request.itemId(), JSON.writeValueAsString(rows), ItemValueType.TEXT);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return CheckResult.failed(request.itemId(),
                    "could not serialise discovery result: " + e.getMessage());
        }
    }

    /** Parses {@code {#IFNAME}=value,{#IFINDEX}=index} into a macro/source map. */
    private static Map<String, String> parseMacroSources(String spec) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return sources;
        }
        for (String pair : spec.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                sources.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return sources;
    }

    /**
     * Maps an SNMP variable onto the item's declared value type.
     *
     * <p>Returns {@code null} when the value cannot be represented, so the
     * caller can report a type mismatch instead of storing something wrong.
     */
    static Object convert(VariableBinding binding, ItemValueType valueType) {
        var variable = binding.getVariable();

        if (valueType == null || !valueType.isNumeric()) {
            // OctetString may hold binary; toString on a printable string is
            // correct, and hex for anything else, which is what snmp4j does.
            return variable instanceof OctetString octets ? octets.toString() : variable.toString();
        }

        Long numeric = switch (variable) {
            case Counter64 counter64 -> counter64.getValue();
            case Counter32 counter32 -> counter32.getValue();
            case Gauge32 gauge32 -> gauge32.getValue();
            case Integer32 integer32 -> (long) integer32.getValue();
            // TimeTicks is hundredths of a second on the wire. Converting here
            // means an uptime threshold reads in seconds, the unit every
            // operator actually thinks in.
            case TimeTicks timeTicks -> timeTicks.toMilliseconds() / 1000;
            default -> null;
        };

        if (numeric == null) {
            // Some agents return counters as OctetStrings. Parse rather than
            // reject: the alternative is an item permanently unsupported on
            // otherwise working hardware.
            try {
                String text = variable.toString().trim();
                return valueType == ItemValueType.FLOAT
                        ? (Object) Double.parseDouble(text)
                        : (Object) Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return valueType == ItemValueType.FLOAT ? (Object) numeric.doubleValue() : (Object) numeric;
    }
}
