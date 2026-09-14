package com.nms.collector.net;

import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tests whether a TCP port accepts connections, and how long that takes.
 *
 * <p>Two item keys are supported, matching the Zabbix convention:
 * {@code net.tcp.service[...]} yields 1/0, and {@code net.tcp.service.perf[...]}
 * yields the connect time in seconds.
 *
 * <p>A refused connection is a successful measurement of a closed port, not a
 * collection failure, so it returns 0 rather than an error. That distinction
 * matters: an item in the error state stops feeding its triggers, which would
 * mean a service going down silently stops alerting.
 */
@Component
public class TcpPortPoller implements Poller {

    @Override
    public CheckType checkType() {
        return CheckType.TCP_PORT;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        int port = resolvePort(request);
        if (port <= 0 || port > 65535) {
            return CheckResult.failed(request.itemId(),
                    "Invalid or missing TCP port for item key '" + request.key() + "'");
        }

        boolean wantsTiming = request.key().startsWith("net.tcp.service.perf")
                || "true".equalsIgnoreCase(request.param("perf", "false"));

        long startNanos = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(request.address(), port),
                    (int) request.timeout().toMillis());
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

            return wantsTiming
                    ? CheckResult.ok(request.itemId(), seconds, ItemValueType.FLOAT)
                    : CheckResult.ok(request.itemId(), 1L, ItemValueType.UNSIGNED);
        } catch (IOException e) {
            // Refused, filtered or timed out: the service is not available.
            // perf items report 0 seconds, which conventionally means "down".
            return wantsTiming
                    ? CheckResult.ok(request.itemId(), 0.0, ItemValueType.FLOAT)
                    : CheckResult.ok(request.itemId(), 0L, ItemValueType.UNSIGNED);
        }
    }

    /**
     * Port may come from the item's params, the host interface, or the bracket
     * parameters of the key itself, in that order of specificity.
     */
    static int resolvePort(CheckRequest request) {
        int fromParams = request.intParam("port", 0);
        if (fromParams > 0) {
            return fromParams;
        }
        if (request.port() > 0) {
            return request.port();
        }
        return defaultPortForService(serviceName(request.key()));
    }

    /** Extracts {@code http} from {@code net.tcp.service[http,,8080]}. */
    private static String serviceName(String key) {
        int open = key.indexOf('[');
        if (open < 0) {
            return "";
        }
        int close = key.lastIndexOf(']');
        String args = key.substring(open + 1, close < 0 ? key.length() : close);
        int comma = args.indexOf(',');
        return (comma < 0 ? args : args.substring(0, comma)).trim().toLowerCase();
    }

    private static int defaultPortForService(String service) {
        return switch (service) {
            case "ssh" -> 22;
            case "telnet" -> 23;
            case "smtp" -> 25;
            case "dns" -> 53;
            case "http" -> 80;
            case "pop" -> 110;
            case "ntp" -> 123;
            case "imap" -> 143;
            case "ldap" -> 389;
            case "https" -> 443;
            case "smb" -> 445;
            case "rtsp" -> 554;
            case "rdp" -> 3389;
            case "postgresql" -> 5432;
            case "mysql" -> 3306;
            case "redis" -> 6379;
            case "onvif" -> 80;
            default -> 0;
        };
    }
}
