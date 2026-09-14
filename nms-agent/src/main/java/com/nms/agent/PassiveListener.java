package com.nms.agent;

import com.nms.common.protocol.AgentCodec;
import com.nms.common.protocol.AgentProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Answers server-initiated checks.
 *
 * <p>Accepts a connection, reads one item key, replies with its value, and
 * closes. One request per connection, deliberately: it keeps the protocol
 * trivial to reason about and means a stalled server cannot tie up an agent
 * thread indefinitely.
 */
public class PassiveListener {

    private static final Logger log = LoggerFactory.getLogger(PassiveListener.class);

    /**
     * Concurrent requests served.
     *
     * <p>Small on purpose. The agent runs on a machine whose job is something
     * else, and a monitoring agent that competes for that machine's resources
     * has defeated its own purpose.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 8;

    private final AgentConfig config;
    private final MetricCollector collector;

    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;
    private volatile boolean running;

    public PassiveListener(AgentConfig config, MetricCollector collector) {
        this.config = config;
        this.collector = collector;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.listenAddress(), config.listenPort()));

            workers = new ThreadPoolExecutor(1, MAX_CONCURRENT_REQUESTS,
                    30, TimeUnit.SECONDS,
                    // A synchronous queue plus a caller-runs policy means a
                    // burst is throttled rather than queued: the server waits,
                    // which is correct, instead of the agent accumulating work.
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "agent-request");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy());

            running = true;
            acceptThread = new Thread(this::acceptLoop, "agent-accept");
            acceptThread.setDaemon(false);
            acceptThread.start();

            log.info("Listening for passive checks on {}:{} (allowed servers: {})",
                    config.listenAddress(), config.listenPort(), config.allowedServers());

        } catch (IOException e) {
            throw new IllegalStateException("Could not listen on " + config.listenAddress()
                    + ":" + config.listenPort() + ": " + e.getMessage(), e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    log.warn("Error accepting a connection: {}", e.getMessage());
                }
                // Otherwise the socket was closed during shutdown, which is
                // exactly what is supposed to happen.
            }
        }
    }

    private void handle(Socket client) {
        String peer = client.getInetAddress().getHostAddress();

        try (client) {
            // Checked before a single byte is read. An agent that answers the
            // whole network lets anyone who can reach the port enumerate this
            // host's filesystems, processes and logged-in users.
            if (!config.isServerAllowed(peer)) {
                log.warn("Refused a passive check from {}, which is not in the allowed server list", peer);
                return;
            }

            client.setSoTimeout(config.timeout() * 1000);

            AgentCodec.Frame request = AgentCodec.read(client.getInputStream());
            String key = request.payload().trim();

            String value = collector.collect(key);
            AgentCodec.write(client.getOutputStream(), AgentProtocol.FLAG_TEXT, value);

            if (log.isDebugEnabled()) {
                log.debug("Served '{}' to {} -> {}", key, peer, abbreviate(value));
            }

        } catch (IOException e) {
            // A server that times out or hangs up mid-request is ordinary, not
            // exceptional, so this stays at debug.
            log.debug("Request from {} failed: {}", peer, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected failure serving a request from {}", peer, e);
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.debug("Error closing the listener: {}", e.getMessage());
        }
        if (workers != null) {
            workers.shutdown();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        log.info("Passive listener stopped");
    }

    private static String abbreviate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "...";
    }
}
