package com.nms.server;

import com.nms.collector.CollectorConfiguration;
import com.nms.server.repository.SupportRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the monitoring server.
 *
 * <p>The server is stateless: scheduling state lives in the database rather
 * than in memory, and work is claimed with {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}. Several instances can therefore run behind a load balancer, sharing
 * the poller and alert queues with no leader election and no sticky sessions --
 * which is what makes horizontal scaling and rolling deployment possible in a
 * hosted environment.
 */
// The default in-memory user is excluded: authentication is entirely
// token-based, and leaving it enabled prints a generated password at every
// startup that looks like a real credential and is not one.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableAsync
@Import(CollectorConfiguration.class)
@EnableJpaRepositories(
        basePackageClasses = SupportRepositories.class,
        // The small repositories are grouped as nested interfaces inside
        // CoreRepositories; Spring Data skips those unless told otherwise.
        considerNestedRepositories = true)
public class NmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(NmsApplication.class, args);
    }
}
