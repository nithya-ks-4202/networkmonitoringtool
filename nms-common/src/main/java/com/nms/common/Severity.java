package com.nms.common;

/** Problem severity, ordered from least to most urgent. */
public enum Severity {
    NOT_CLASSIFIED(0),
    INFORMATION(1),
    WARNING(2),
    AVERAGE(3),
    HIGH(4),
    DISASTER(5);

    private final int level;

    Severity(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public static Severity ofLevel(int level) {
        for (Severity s : values()) {
            if (s.level == level) {
                return s;
            }
        }
        throw new IllegalArgumentException("No severity with level " + level);
    }

    public boolean atLeast(Severity other) {
        return this.level >= other.level;
    }
}
