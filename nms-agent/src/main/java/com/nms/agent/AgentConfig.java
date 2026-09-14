package com.nms.agent;

import com.nms.common.protocol.AgentProtocol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The agent's settings.
 *
 * @param hostname        name this agent reports itself as; must match the
 *                        host's technical name on the server for active checks
 *                        to be matched to it
 * @param listenAddress   address to bind the passive listener to
 * @param listenPort      port for passive checks
 * @param allowedServers  addresses permitted to request passive checks
 * @param passiveEnabled  whether to listen at all
 * @param activeEnabled   whether to fetch and push active checks
 * @param serverUrl       server base URL for active checks
 * @param activeInterval  seconds between refreshes of the active check list
 * @param bufferSize      values held before an active push is forced
 * @param timeout         ceiling on collecting one metric
 * @param allowRemoteCommands whether the server may ask this agent to run a
 *                        command
 */
public record AgentConfig(
        String hostname,
        String listenAddress,
        int listenPort,
        List<String> allowedServers,
        boolean passiveEnabled,
        boolean activeEnabled,
        String serverUrl,
        int activeInterval,
        int bufferSize,
        int timeout,
        boolean allowRemoteCommands) {

    public static AgentConfig from(Properties properties) {
        return new AgentConfig(
                value(properties, "agent.hostname", "NMS_AGENT_HOSTNAME", defaultHostname()),
                value(properties, "agent.listen.address", "NMS_AGENT_LISTEN_ADDRESS", "0.0.0.0"),
                intValue(properties, "agent.listen.port", "NMS_AGENT_LISTEN_PORT",
                        AgentProtocol.DEFAULT_AGENT_PORT),
                // Defaults to loopback only. An agent answering the whole
                // network would let anyone who can reach the port enumerate
                // the host's filesystems, processes and users.
                list(value(properties, "agent.allowed.servers", "NMS_AGENT_ALLOWED_SERVERS",
                        "127.0.0.1,::1")),
                boolValue(properties, "agent.passive.enabled", "NMS_AGENT_PASSIVE_ENABLED", true),
                boolValue(properties, "agent.active.enabled", "NMS_AGENT_ACTIVE_ENABLED", false),
                value(properties, "agent.server.url", "NMS_AGENT_SERVER_URL", "http://localhost:8080"),
                intValue(properties, "agent.active.interval", "NMS_AGENT_ACTIVE_INTERVAL", 120),
                intValue(properties, "agent.buffer.size", "NMS_AGENT_BUFFER_SIZE", 1000),
                intValue(properties, "agent.timeout", "NMS_AGENT_TIMEOUT", 3),
                // Off by default. Remote command execution turns the monitoring
                // system into a remote shell on every host it watches, and that
                // has to be a decision someone makes deliberately.
                boolValue(properties, "agent.allow.remote.commands",
                        "NMS_AGENT_ALLOW_REMOTE_COMMANDS", false));
    }

    /**
     * True when the given address may request passive checks.
     *
     * <p>Matched by prefix so a whole subnet can be permitted with
     * {@code 10.20.} without spelling out every server.
     */
    public boolean isServerAllowed(String address) {
        if (address == null) {
            return false;
        }
        if (allowedServers.contains("0.0.0.0") || allowedServers.contains("*")) {
            return true;
        }
        String normalised = address.startsWith("/") ? address.substring(1) : address;
        return allowedServers.stream().anyMatch(allowed ->
                normalised.equals(allowed) || normalised.startsWith(allowed));
    }

    private static String defaultHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private static String value(Properties properties, String key, String environmentKey,
                                String defaultValue) {
        String fromEnvironment = System.getenv(environmentKey);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        return properties.getProperty(key, defaultValue).trim();
    }

    private static int intValue(Properties properties, String key, String environmentKey,
                                int defaultValue) {
        try {
            return Integer.parseInt(value(properties, key, environmentKey,
                    Integer.toString(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean boolValue(Properties properties, String key, String environmentKey,
                                     boolean defaultValue) {
        String raw = value(properties, key, environmentKey, Boolean.toString(defaultValue))
                .toLowerCase(Locale.ROOT);
        return "true".equals(raw) || "yes".equals(raw) || "1".equals(raw) || "on".equals(raw);
    }

    private static List<String> list(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
