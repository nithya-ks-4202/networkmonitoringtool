package com.nms.server.bootstrap;

import com.nms.server.domain.AppUser;
import com.nms.server.domain.Role;
import com.nms.server.domain.Tenant;
import com.nms.server.domain.UserGroup;
import com.nms.server.repository.CoreRepositories.AppUserRepository;
import com.nms.server.repository.CoreRepositories.RoleRepository;
import com.nms.server.repository.CoreRepositories.UserGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first administrator on an empty installation.
 *
 * <p>Done at startup rather than in a migration so that no password hash is
 * ever committed to source control or shipped identically to every customer.
 * A default administrator password in a monitoring product is a default
 * administrator password on the network it monitors.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserGroupRepository userGroups;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String configuredPassword;

    public AdminBootstrap(AppUserRepository users,
                          RoleRepository roles,
                          UserGroupRepository userGroups,
                          PasswordEncoder passwordEncoder,
                          @Value("${nms.security.admin-username:admin}") String adminUsername,
                          @Value("${nms.security.admin-password:}") String configuredPassword) {
        this.users = users;
        this.roles = roles;
        this.userGroups = userGroups;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.configuredPassword = configuredPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByTenantId(Tenant.DEFAULT_ID)) {
            return;
        }

        Role superAdmin = roles.findByTenantIdAndName(Tenant.DEFAULT_ID, "Super admin")
                .orElseThrow(() -> new IllegalStateException(
                        "The 'Super admin' role is missing. The seed migration did not run."));

        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? generatePassword() : configuredPassword;

        AppUser admin = new AppUser();
        admin.setTenantId(Tenant.DEFAULT_ID);
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("Administrator");
        admin.setRole(superAdmin);
        admin.setEnabled(true);

        userGroups.findByTenantIdAndName(Tenant.DEFAULT_ID, "Administrators")
                .ifPresent(group -> admin.getGroups().add(group));

        users.save(admin);

        if (generated) {
            // Printed once, in a block that is hard to miss in a scrolling log.
            // There is nowhere else it can be shown: this runs before anyone
            // can sign in to be told.
            log.warn("""

                    ============================================================
                     An administrator account has been created.

                       Username: {}
                       Password: {}

                     This password was generated and is shown only once. Sign in
                     and change it, or set NMS_ADMIN_PASSWORD before first start
                     to choose your own.
                    ============================================================
                    """, adminUsername, password);
        } else {
            log.info("Administrator account '{}' created from NMS_ADMIN_PASSWORD", adminUsername);
        }
    }

    /**
     * A password with enough entropy to survive being visible in a log.
     *
     * <p>URL-safe Base64 so it can be pasted into a form or a script without
     * escaping, which is what people actually do with it.
     */
    private static String generatePassword() {
        byte[] material = new byte[24];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }
}
