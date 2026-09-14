package com.nms.common;

import java.time.Duration;
import java.util.Map;

/**
 * A single unit of collection work handed to a poller.
 *
 * <p>This is deliberately a flat, self-contained value: the collector modules
 * must run inside the server and inside an on-premise proxy, and the proxy has
 * no access to the configuration database. Everything a poller needs to
 * execute the check travels with the request.
 *
 * @param itemId    identifier of the item this result will be stored against
 * @param hostId    identifier of the owning host
 * @param hostName  host name, used for logging and macro expansion
 * @param checkType how the value is obtained
 * @param valueType how the returned value must be interpreted
 * @param key       the item key, e.g. {@code net.if.in[eth0]} or {@code icmpping}
 * @param address   resolved IP address or DNS name of the target
 * @param port      target port, or 0 when the check type has no port
 * @param timeout   how long the poller may wait before failing the check
 * @param params    check-type specific parameters (SNMP community, credentials,
 *                  HTTP URL, RTSP path, and so on)
 */
public record CheckRequest(
        long itemId,
        long hostId,
        String hostName,
        CheckType checkType,
        ItemValueType valueType,
        String key,
        String address,
        int port,
        Duration timeout,
        Map<String, String> params) {

    public CheckRequest {
        if (params == null) {
            params = Map.of();
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(3);
        }
    }

    /** Returns the named parameter, or {@code defaultValue} when absent or blank. */
    public String param(String name, String defaultValue) {
        String value = params.get(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /** Returns the named parameter parsed as an int, or {@code defaultValue}. */
    public int intParam(String name, int defaultValue) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
