package com.nms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.protocol.AgentProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects on the agent's own schedule and pushes values to the server.
 *
 * <p>Active mode inverts the connection direction, which matters twice over. It
 * works where the server cannot reach the host -- behind NAT, behind a one-way
 * firewall, on a laptop -- and it scales better, because the server is not
 * holding a connection open per monitored machine.
 *
 * <p>Values are buffered and sent in batches. A host with a hundred items on a
 * one-minute interval would otherwise make a hundred requests a minute for a
 * few hundred bytes of data.
 */
public class ActiveChecksClient {

    private static final Logger log = LoggerFactory.getLogger(ActiveChecksClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfig config;
    private final MetricCollector collector;
    private final HttpClient httpClient;

    /** The checks the server has asked this agent to run, keyed by item id. */
    private final Map<Long, ActiveCheckState> checks = new ConcurrentHashMap<>();

    /** Collected values awaiting upload. */
    private final ConcurrentLinkedQueue<AgentProtocol.AgentValue> pending = new ConcurrentLinkedQueue<>();

    /**
     * Identifies this run of the agent.
     *
     * <p>Lets the server tell a restart from a network blip: a new session on
     * the same host means the agent restarted, and any gap in values is
     * explained rather than mysterious.
     */
    private final String session = UUID.randomUUID().toString();

    private ScheduledExecutorService scheduler;

    public ActiveChecksClient(AgentConfig config, MetricCollector collector) {
        this.config = config;
        this.collector = collector;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void start() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "agent-active");
            thread.setDaemon(true);
            return thread;
        });

        // Refreshing the check list and collecting are separate schedules: the
        // list changes rarely, the values constantly.
        scheduler.scheduleWithFixedDelay(this::refreshChecks, 0, config.activeInterval(), TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::collectAndSend, 5, 1, TimeUnit.SECONDS);

        log.info("Active checks enabled, reporting to {} as '{}'",
                config.serverUrl(), config.hostname());
    }

    /** Asks the server which items this host should collect. */
    private void refreshChecks() {
        try {
            String body = JSON.writeValueAsString(new AgentProtocol.ActiveChecksRequest(
                    AgentProtocol.ActiveChecksRequest.REQUEST_TYPE,
                    config.hostname(), "", AgentMain.VERSION));

            HttpResponse<String> response = post("/api/agent/checks", body);

            if (response.statusCode() == 404) {
                // The host is not configured on the server yet. Expected while
                // an estate is being rolled out, so it is not an error.
                log.info("The server does not know a host named '{}' yet", config.hostname());
                return;
            }
            if (response.statusCode() != 200) {
                log.warn("Active check refresh returned HTTP {}", response.statusCode());
                return;
            }

            AgentProtocol.ActiveChecksResponse checksResponse =
                    JSON.readValue(response.body(), AgentProtocol.ActiveChecksResponse.class);

            applyChecks(checksResponse.data() == null ? List.of() : checksResponse.data());

        } catch (IOException e) {
            log.warn("Could not refresh active checks: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyChecks(List<AgentProtocol.ActiveCheck> incoming) {
        // Due times survive a refresh, so an unrelated configuration change
        // does not restart every item's cycle and cause a burst of collection.
        checks.keySet().retainAll(incoming.stream().map(AgentProtocol.ActiveCheck::itemId).toList());

        for (AgentProtocol.ActiveCheck check : incoming) {
            checks.computeIfAbsent(check.itemId(),
                    itemId -> new ActiveCheckState(check, Instant.now()));
        }

        if (!incoming.isEmpty()) {
            log.info("Active check list refreshed: {} item(s)", checks.size());
        }
    }

    /** Collects whatever is due, then uploads if there is enough to be worth it. */
    private void collectAndSend() {
        Instant now = Instant.now();

        for (ActiveCheckState state : checks.values()) {
            if (state.dueAt.isAfter(now)) {
                continue;
            }
            state.dueAt = now.plusSeconds(Math.max(1, state.check.delaySeconds()));

            String value = collector.collect(state.check.key());
            boolean notSupported = value.startsWith(AgentProtocol.NOT_SUPPORTED_PREFIX);

            pending.add(new AgentProtocol.AgentValue(
                    state.check.itemId(), state.check.key(),
                    notSupported ? null : value,
                    now.getEpochSecond(), now.getNano(),
                    notSupported ? "NOT_SUPPORTED" : "NORMAL",
                    notSupported ? value.substring(AgentProtocol.NOT_SUPPORTED_PREFIX.length()).trim() : null));
        }

        if (!pending.isEmpty()) {
            send();
        }
    }

    private void send() {
        List<AgentProtocol.AgentValue> batch = new ArrayList<>();
        AgentProtocol.AgentValue value;
        while (batch.size() < config.bufferSize() && (value = pending.poll()) != null) {
            batch.add(value);
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            String body = JSON.writeValueAsString(new AgentProtocol.AgentDataRequest(
                    AgentProtocol.AgentDataRequest.REQUEST_TYPE,
                    config.hostname(), session, batch));

            HttpResponse<String> response = post("/api/agent/data", body);

            if (response.statusCode() != 200) {
                log.warn("Upload of {} value(s) returned HTTP {}; they will be retried",
                        batch.size(), response.statusCode());
                requeue(batch);
            }

        } catch (IOException e) {
            log.warn("Could not upload {} value(s): {}", batch.size(), e.getMessage());
            requeue(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requeue(batch);
        }
    }

    /**
     * Returns a failed batch to the buffer, oldest values first.
     *
     * <p>Bounded: if the server stays unreachable the oldest values are dropped
     * rather than accumulated. An agent that exhausts the memory of the machine
     * it is monitoring has caused a worse outage than the one it was watching
     * for.
     */
    private void requeue(List<AgentProtocol.AgentValue> batch) {
        int capacity = config.bufferSize() * 10;
        if (pending.size() + batch.size() > capacity) {
            int toDrop = pending.size() + batch.size() - capacity;
            for (int i = 0; i < toDrop && pending.poll() != null; i++) {
                // Oldest first: an operator looking at this host now needs the
                // recent values far more than the ones from an hour ago.
            }
            log.warn("Agent buffer is full; discarded {} of the oldest value(s)", toDrop);
        }
        pending.addAll(batch);
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.serverUrl() + path))
                .timeout(Duration.ofSeconds(Math.max(5, config.timeout() * 3)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        // One last push, so a planned restart does not leave a gap in the data.
        if (!pending.isEmpty()) {
            log.info("Flushing {} buffered value(s) before shutdown", pending.size());
            send();
        }
    }

    /** One active check and when it is next due. */
    private static final class ActiveCheckState {
        private final AgentProtocol.ActiveCheck check;
        private volatile Instant dueAt;

        ActiveCheckState(AgentProtocol.ActiveCheck check, Instant dueAt) {
            this.check = check;
            this.dueAt = dueAt;
        }
    }
}
