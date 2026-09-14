package com.nms.server.domain;

/** The delivery mechanism a media type uses. */
public enum MediaTypeKind {
    EMAIL,
    SLACK,
    WEBHOOK,
    SCRIPT,
    SMS,
    TEAMS,
    PAGERDUTY,
    OPSGENIE,
    TELEGRAM
}
