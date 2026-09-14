package com.nms.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Records the last time this proxy successfully reached the server.
 *
 * <p>The proxy listens on nothing, so it has no health endpoint to poll. That
 * is deliberate -- a port open inside a customer network needs a reason, and
 * "so the container runtime can ask if we are alive" is not a good one.
 *
 * <p>A file touched on each successful contact answers the same question and a
 * more useful one besides. An HTTP 200 from the proxy's own health endpoint
 * would only prove its JVM is running; a fresh heartbeat proves it is running
 * <em>and</em> talking to the server. A proxy that is up but has been unable to
 * reach the server for an hour is the failure that actually matters, and it is
 * exactly the one a self-reported health endpoint would call healthy.
 */
@Component
public class Heartbeat {

    private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

    private final Path file;

    public Heartbeat(ProxyProperties properties) {
        this.file = Paths.get(properties.getHeartbeatFile());
    }

    @PostConstruct
    void prepare() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("Could not create the directory for the heartbeat file {}: {}. "
                    + "The container health check will report unhealthy.", file, e.getMessage());
        }
    }

    /**
     * Records contact with the server.
     *
     * <p>Called after a successful upload or configuration fetch -- either
     * proves the link is working. Failures are logged once at debug and
     * otherwise ignored: an unwritable heartbeat is a monitoring gap, not a
     * reason to stop collecting.
     */
    public void record() {
        try {
            // The timestamp is written as content as well as being the file's
            // mtime, so an operator running `cat` gets an answer without
            // reaching for stat.
            Files.writeString(file, Instant.now().toString() + '\n', StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not write the heartbeat file {}: {}", file, e.getMessage());
        }
    }

    /** Where the heartbeat is written, for logging and for the health check. */
    public Path file() {
        return file;
    }
}
