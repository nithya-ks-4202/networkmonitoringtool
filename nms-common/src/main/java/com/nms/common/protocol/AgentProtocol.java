package com.nms.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Wire format spoken between the server/proxy and the monitoring agent.
 *
 * <p>The framing mirrors the Zabbix agent protocol so existing operational
 * knowledge carries over: a 4-byte magic {@code NMS\1}, one flags byte, then an
 * 8-byte little-endian payload length, then the JSON (or plain text) payload.
 * A length prefix is what lets the reader refuse an oversized payload before
 * allocating for it.
 */
public final class AgentProtocol {

    /** Header magic identifying a framed agent message. */
    public static final byte[] MAGIC = "NMS\1".getBytes(StandardCharsets.US_ASCII);

    /** Total header size: 4 magic + 1 flags + 8 length. */
    public static final int HEADER_LENGTH = 13;

    /** Payload is a UTF-8 JSON document. */
    public static final byte FLAG_JSON = 0x01;

    /** Payload is a bare UTF-8 value (passive single-key response). */
    public static final byte FLAG_TEXT = 0x02;

    /** Hard ceiling on a single payload, to bound memory use per connection. */
    public static final long MAX_PAYLOAD_BYTES = 16L * 1024 * 1024;

    /** Value returned by an agent when it does not implement the requested key. */
    public static final String NOT_SUPPORTED_PREFIX = "NMS_NOTSUPPORTED";

    /** Default TCP port the agent listens on for passive checks. */
    public static final int DEFAULT_AGENT_PORT = 10150;

    /** Default TCP port the server listens on for active agent and proxy traffic. */
    public static final int DEFAULT_SERVER_PORT = 10151;

    private AgentProtocol() {
    }

    /** Request sent by an agent asking which active checks it should run. */
    public record ActiveChecksRequest(String request, String host, String hostMetadata, String version) {
        public static final String REQUEST_TYPE = "active.checks";
    }

    /** One active check the agent should collect on its own schedule. */
    public record ActiveCheck(long itemId, String key, int delaySeconds, long lastLogSize, int mtime) {
    }

    /** Server's reply listing the agent's active checks. */
    public record ActiveChecksResponse(String response, List<ActiveCheck> data, String info) {
    }

    /** A value an agent collected and is pushing to the server. */
    public record AgentValue(long itemId, String key, String value, long clock, int ns, String state, String error) {
    }

    /** Batch of agent-collected values. */
    public record AgentDataRequest(String request, String host, String session, List<AgentValue> data) {
        public static final String REQUEST_TYPE = "agent.data";
    }

    /** Server's acknowledgement of an agent value batch. */
    public record AgentDataResponse(String response, String info) {
    }
}
