package com.nms.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * The monitoring agent.
 *
 * <p>Runs on each monitored host and answers questions about it. Deliberately a
 * plain executable jar rather than a Spring application: it is installed on
 * every server in an estate, so it has to start in well under a second and hold
 * a footprint small enough that nobody objects to it being there.
 *
 * <p>Two modes, which can run together.
 *
 * <p><b>Passive</b>: the agent listens and the server asks. The default inside a
 * data centre, where the server can reach the host directly.
 *
 * <p><b>Active</b>: the agent asks the server what to collect, then pushes
 * values back. Needed when the host sits behind NAT or a one-way firewall, and
 * it scales better -- the server is not holding a connection per host.
 */
public final class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static final String VERSION = "1.0.0";

    private AgentMain() {
    }

    public static void main(String[] args) {
        AgentConfig config = loadConfig(args);

        log.info("NMS agent {} starting on {}", VERSION, config.hostname());

        MetricCollector collector = new MetricCollector();
        PassiveListener passiveListener = null;
        ActiveChecksClient activeClient = null;

        if (config.passiveEnabled()) {
            passiveListener = new PassiveListener(config, collector);
            passiveListener.start();
        }

        if (config.activeEnabled()) {
            activeClient = new ActiveChecksClient(config, collector);
            activeClient.start();
        }

        if (passiveListener == null && activeClient == null) {
            log.error("Neither passive nor active checks are enabled; the agent has nothing to do. "
                    + "Set agent.passive.enabled or agent.active.enabled.");
            System.exit(1);
        }

        // A clean shutdown lets the active client flush values it has collected
        // but not yet sent, so a restart does not punch a hole in the graphs.
        PassiveListener listenerToStop = passiveListener;
        ActiveChecksClient clientToStop = activeClient;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            if (listenerToStop != null) {
                listenerToStop.stop();
            }
            if (clientToStop != null) {
                clientToStop.stop();
            }
        }, "agent-shutdown"));

        log.info("Agent ready");
    }

    /**
     * Loads configuration from a file, then the environment.
     *
     * <p>The environment wins, because that is what a container orchestrator
     * sets and it has to be able to override an image's baked-in file.
     */
    private static AgentConfig loadConfig(String[] args) {
        Properties properties = new Properties();

        String configPath = args.length > 0 ? args[0]
                : System.getenv().getOrDefault("NMS_AGENT_CONFIG", "/etc/nms/agent.conf");

        Path path = Paths.get(configPath);
        if (Files.isReadable(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                properties.load(reader);
                log.info("Loaded configuration from {}", path);
            } catch (IOException e) {
                log.warn("Could not read {}: {}. Using defaults and environment.",
                        path, e.getMessage());
            }
        }

        return AgentConfig.from(properties);
    }
}
