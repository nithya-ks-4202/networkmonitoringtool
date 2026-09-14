package com.nms.server.domain;

/**
 * Broad classification of a host.
 *
 * <p>Drives default template suggestions and lets the interface group devices
 * whose health models genuinely differ -- a camera is judged on whether it is
 * streaming, a server on resource pressure.
 */
public enum HostClass {
    GENERIC,
    SERVER,
    NETWORK_DEVICE,
    CAMERA,
    PRINTER,
    UPS,
    STORAGE,
    HYPERVISOR,
    APPLICATION,
    CLOUD
}
