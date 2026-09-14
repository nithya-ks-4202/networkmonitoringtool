package com.nms.server.domain;

/** What an action operation does when it runs. */
public enum OperationType {
    SEND_MESSAGE,
    /** Run a script, on the server, a proxy, or the affected host via the agent. */
    REMOTE_COMMAND,
    WEBHOOK,
    /** Discovery operations: react to a device appearing on the network. */
    ADD_HOST,
    REMOVE_HOST,
    ENABLE_HOST,
    DISABLE_HOST,
    ADD_TO_GROUP,
    REMOVE_FROM_GROUP,
    LINK_TEMPLATE,
    UNLINK_TEMPLATE,
    SET_HOST_INVENTORY_MODE
}
