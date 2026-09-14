package com.nms.server.security;

import java.util.List;
import java.util.Set;

/**
 * The permission vocabulary.
 *
 * <p>Named constants rather than loose strings so that a typo in a
 * {@code @PreAuthorize} expression is a compile error instead of an endpoint
 * that silently denies everyone -- or, worse, a role that silently grants
 * nothing and is only noticed when someone cannot do their job during an
 * incident.
 */
public final class Permissions {

    private Permissions() {
    }

    /**
     * Grants everything.
     *
     * <p>Expanded into the concrete set when authorities are built, because
     * Spring's {@code hasAuthority('host.read')} is an exact string match and
     * would never match a literal asterisk.
     */
    public static final String ALL = "*";

    public static final String HOST_READ = "host.read";
    public static final String HOST_WRITE = "host.write";
    public static final String ITEM_READ = "item.read";
    public static final String ITEM_WRITE = "item.write";
    public static final String TRIGGER_READ = "trigger.read";
    public static final String TRIGGER_WRITE = "trigger.write";
    public static final String TEMPLATE_READ = "template.read";
    public static final String TEMPLATE_WRITE = "template.write";
    public static final String ACTION_READ = "action.read";
    public static final String ACTION_WRITE = "action.write";
    public static final String MEDIATYPE_READ = "mediatype.read";
    public static final String MEDIATYPE_WRITE = "mediatype.write";
    public static final String MAINTENANCE_READ = "maintenance.read";
    public static final String MAINTENANCE_WRITE = "maintenance.write";
    public static final String PROBLEM_READ = "problem.read";
    public static final String PROBLEM_ACKNOWLEDGE = "problem.acknowledge";
    public static final String DASHBOARD_READ = "dashboard.read";
    public static final String DASHBOARD_WRITE = "dashboard.write";
    public static final String MAP_READ = "map.read";
    public static final String MAP_WRITE = "map.write";
    public static final String DISCOVERY_READ = "discovery.read";
    public static final String DISCOVERY_WRITE = "discovery.write";
    public static final String PROXY_READ = "proxy.read";
    public static final String PROXY_WRITE = "proxy.write";
    public static final String USER_READ = "user.read";
    public static final String USER_WRITE = "user.write";
    public static final String REPORT_READ = "report.read";
    public static final String ADMIN_READ = "admin.read";
    public static final String ADMIN_WRITE = "admin.write";

    /** Every concrete permission the product defines. */
    public static final Set<String> ALL_PERMISSIONS = Set.of(
            HOST_READ, HOST_WRITE,
            ITEM_READ, ITEM_WRITE,
            TRIGGER_READ, TRIGGER_WRITE,
            TEMPLATE_READ, TEMPLATE_WRITE,
            ACTION_READ, ACTION_WRITE,
            MEDIATYPE_READ, MEDIATYPE_WRITE,
            MAINTENANCE_READ, MAINTENANCE_WRITE,
            PROBLEM_READ, PROBLEM_ACKNOWLEDGE,
            DASHBOARD_READ, DASHBOARD_WRITE,
            MAP_READ, MAP_WRITE,
            DISCOVERY_READ, DISCOVERY_WRITE,
            PROXY_READ, PROXY_WRITE,
            USER_READ, USER_WRITE,
            REPORT_READ,
            ADMIN_READ, ADMIN_WRITE);

    /**
     * Expands a role's permission list into the authorities to grant.
     *
     * <p>The wildcard becomes the full set here rather than being special-cased
     * at every check. Doing it once means a new permission added to this class
     * is automatically held by administrators, instead of quietly excluding
     * them from a feature until someone remembers to update their role.
     */
    public static Set<String> expand(List<String> granted) {
        if (granted == null || granted.isEmpty()) {
            return Set.of();
        }
        if (granted.contains(ALL)) {
            return ALL_PERMISSIONS;
        }
        return Set.copyOf(granted);
    }
}
