package com.nms.server.domain;

/** SNMP protocol version configured on an interface. */
public enum SnmpVersion {
    V1,
    V2C,
    /** The only version with authentication and encryption. */
    V3
}
