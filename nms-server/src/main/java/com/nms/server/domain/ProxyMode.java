package com.nms.server.domain;

/** Which side opens the connection between server and proxy. */
public enum ProxyMode {
    /**
     * The proxy dials the server. The only mode that works when the proxy is
     * behind NAT or a firewall that permits no inbound traffic, which is every
     * customer network in a hosted deployment.
     */
    ACTIVE,
    /** The server dials the proxy; for on-premise installs of the whole platform. */
    PASSIVE
}
