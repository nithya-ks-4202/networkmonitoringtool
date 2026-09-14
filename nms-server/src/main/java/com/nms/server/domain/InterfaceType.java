package com.nms.server.domain;

/** Transport used to reach a host. */
public enum InterfaceType {
    AGENT,
    SNMP,
    IPMI,
    JMX,
    HTTP,
    RTSP,
    ONVIF;

    /** Port used when an interface of this type does not specify one. */
    public int defaultPort() {
        return switch (this) {
            case AGENT -> 10150;
            case SNMP -> 161;
            case IPMI -> 623;
            case JMX -> 12345;
            case HTTP, ONVIF -> 80;
            case RTSP -> 554;
        };
    }
}
