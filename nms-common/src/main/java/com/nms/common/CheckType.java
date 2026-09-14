package com.nms.common;

/**
 * The mechanism used to obtain an item's value.
 *
 * <p>Passive types are polled by the server or a proxy on a schedule. Active
 * types are pushed by an agent or an external system and are never scheduled.
 */
public enum CheckType {
    /** Server connects to the agent and requests a key. */
    AGENT_PASSIVE(true),
    /** Agent connects to the server and uploads values it collected. */
    AGENT_ACTIVE(false),
    /** SNMP GET/GETNEXT against an OID. */
    SNMP(true),
    /** Value delivered by an SNMP trap matched to this item. */
    SNMP_TRAP(false),
    /** ICMP echo request: reachability, loss and round-trip time. */
    ICMP_PING(true),
    /** TCP connect to a port: reachability and connect latency. */
    TCP_PORT(true),
    /** HTTP(S) request: status code, response time or body content. */
    HTTP_AGENT(true),
    /** RTSP OPTIONS handshake, used to verify a camera stream endpoint. */
    RTSP(true),
    /** ONVIF device-service probe, used for IP camera health and metadata. */
    ONVIF(true),
    /** Shell script or binary executed by the server or proxy. */
    EXTERNAL_SCRIPT(true),
    /** SQL query executed against a configured data source. */
    DATABASE(true),
    /** JMX attribute read from a Java process. */
    JMX(true),
    /** Value computed from other items' values rather than collected. */
    CALCULATED(true),
    /** Value derived by aggregating an item across a host group. */
    AGGREGATE(true),
    /** Value submitted through the REST API by an external producer. */
    TRAPPER(false),
    /** Internal self-monitoring metric produced by the server itself. */
    INTERNAL(true),
    /** Item has no collection mechanism; populated by low-level discovery. */
    DEPENDENT(false);

    private final boolean scheduled;

    CheckType(boolean scheduled) {
        this.scheduled = scheduled;
    }

    /** True when the poller scheduler is responsible for driving this item. */
    public boolean isScheduled() {
        return scheduled;
    }
}
