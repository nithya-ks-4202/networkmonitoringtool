package com.nms.server.domain;

/** How a macro's value is stored and whether it may be read back. */
public enum MacroType {
    TEXT,
    /** Never returned by the API and masked in the interface. */
    SECRET,
    /** Resolved at use time from an external secret store. */
    VAULT;

    /**
     * Segments that make a macro a credential rather than a setting.
     *
     * <p>Matched as whole segments of the name, so {@code {$API.KEY}} is a
     * secret and {@code {$MONKEY.COUNT}} is not.
     */
    private static final java.util.Set<String> SECRET_SEGMENTS = java.util.Set.of(
            "PASSWORD", "PASSPHRASE", "SECRET", "TOKEN", "KEY", "CREDENTIAL", "COMMUNITY");

    /**
     * How a macro should be stored, judged from its name.
     *
     * <p>Every macro created through the API used to be {@link #TEXT}, which
     * made {@link #SECRET} unreachable: the masking below it was written and
     * then never triggered, so a camera's password was handed back in clear by
     * {@code GET /api/hosts/{id}} to anyone who could read the host. An SNMP
     * community string was protected and the camera password beside it was
     * not, which is not a distinction anybody chose.
     *
     * <p>Judged from the name because the alternative is asking: a macro map
     * of plain strings cannot carry a type, and a client that has to opt in
     * will forget on exactly the macro that matters. A name wrongly read as
     * secret costs an operator the ability to read one setting back; a name
     * wrongly read as public leaks a credential. Those are not comparable, so
     * this errs towards secrecy.
     */
    public static MacroType defaultFor(String macroName) {
        if (macroName == null) {
            return TEXT;
        }
        String bare = macroName.trim();
        if (bare.startsWith("{$")) {
            bare = bare.substring(2);
        }
        if (bare.endsWith("}")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        // Context macros carry a discovery reference -- {$IFNAME:"eth0"} --
        // which is not part of the name being classified.
        int context = bare.indexOf(':');
        if (context >= 0) {
            bare = bare.substring(0, context);
        }
        for (String segment : bare.toUpperCase(java.util.Locale.ROOT).split("[._]")) {
            if (SECRET_SEGMENTS.contains(segment)) {
                return SECRET;
            }
        }
        return TEXT;
    }
}
