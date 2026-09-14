package com.nms.server.domain;

/** The kinds of panel a dashboard can show. */
public enum WidgetType {
    PROBLEMS,
    PROBLEM_SEVERITY,
    GRAPH,
    SVG_GRAPH,
    GAUGE,
    ITEM_VALUE,
    CLOCK,
    MAP,
    HOST_AVAILABILITY,
    SYSTEM_INFO,
    TOP_HOSTS,
    URL,
    /** Grid of camera online/offline state, for a wall display. */
    CAMERA_WALL,
    DISCOVERY_STATUS,
    ACTION_LOG,
    DATA_OVERVIEW
}
