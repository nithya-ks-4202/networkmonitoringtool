package com.nms.server.util;

import java.time.Duration;

/**
 * Renders durations the way an operator would say them out loud.
 *
 * <p>Used by notifications, the problem list and the latest-data view, so it
 * lives here rather than in any one of them: "how long has this been broken"
 * should read identically in an email and on screen.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * Formats a duration as {@code 45s}, {@code 3m 20s}, {@code 2h 15m} or
     * {@code 4d 6h}.
     *
     * <p>Two units at most. An incident that has run for four days does not
     * become clearer by also being told the seconds.
     */
    public static String human(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());

        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
        return (seconds / 86_400) + "d " + ((seconds % 86_400) / 3600) + "h";
    }
}
