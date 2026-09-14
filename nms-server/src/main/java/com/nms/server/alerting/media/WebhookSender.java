package com.nms.server.alerting.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.server.domain.Alert;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.MediaTypeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Delivers alerts to Slack, Microsoft Teams, PagerDuty and generic webhooks.
 *
 * <p>These share one implementation because they differ only in the JSON they
 * expect; the transport, the retry classification and the timeout handling are
 * identical, and splitting them would mean maintaining four copies of the part
 * that actually matters.
 *
 * <p>HTTP status decides whether a failure is worth retrying. 4xx means the
 * request itself is wrong -- a revoked webhook URL, a malformed payload -- and
 * no number of retries will change that. 5xx and 429 are the provider's
 * problem and usually pass.
 */
@Component
public class WebhookSender implements MediaSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * The channels this sender covers.
     *
     * <p>{@link #kind()} returns the generic one; the registry consults
     * {@link #handles(MediaTypeKind)} so a single bean can serve several kinds.
     */
    private static final List<MediaTypeKind> SUPPORTED = List.of(
            MediaTypeKind.WEBHOOK, MediaTypeKind.SLACK, MediaTypeKind.TEAMS,
            MediaTypeKind.PAGERDUTY, MediaTypeKind.OPSGENIE, MediaTypeKind.TELEGRAM);

    @Override
    public MediaTypeKind kind() {
        return MediaTypeKind.WEBHOOK;
    }

    /** True when this sender can deliver for the given channel kind. */
    public boolean handles(MediaTypeKind kind) {
        return SUPPORTED.contains(kind);
    }

    @Override
    public void send(Alert alert, MediaType mediaType) throws DeliveryException {
        String url = resolveUrl(alert, mediaType);
        if (url == null || url.isBlank()) {
            throw new PermanentDeliveryException(
                    "media type '" + mediaType.getName() + "' has no webhook URL configured");
        }

        String payload = buildPayload(alert, mediaType);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, mediaType.getTimeoutSeconds())))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        applyConfiguredHeaders(mediaType, builder);

        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                log.debug("{} webhook accepted alert {} (HTTP {})",
                        mediaType.getType(), alert.getId(), status);
                return;
            }

            String detail = "HTTP " + status + ": " + abbreviate(response.body());

            // 429 is rate limiting, which is explicitly temporary even though
            // it is a 4xx.
            if (status == 429 || status >= 500) {
                throw new TransientDeliveryException(detail);
            }
            throw new PermanentDeliveryException(detail);

        } catch (IOException e) {
            throw new TransientDeliveryException("could not reach " + hostOf(url) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientDeliveryException("interrupted while delivering the webhook", e);
        }
    }

    private String resolveUrl(Alert alert, MediaType mediaType) {
        if (mediaType.getType() == MediaTypeKind.PAGERDUTY) {
            return mediaType.configString("apiUrl", "https://events.pagerduty.com/v2/enqueue");
        }
        String configured = mediaType.configString("webhookUrl", mediaType.configString("url", ""));
        // A per-user destination overrides the channel default, which is how
        // one Slack media type serves several teams' channels.
        return alert.getSendTo() != null && alert.getSendTo().startsWith("http")
                ? alert.getSendTo()
                : configured;
    }

    private void applyConfiguredHeaders(MediaType mediaType, HttpRequest.Builder builder) {
        Object headers = mediaType.getConfig().get("headers");
        if (!(headers instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            try {
                builder.header(String.valueOf(key), String.valueOf(value));
            } catch (IllegalArgumentException e) {
                // Restricted headers are rejected by HttpClient; skipping one
                // beats failing the whole delivery.
                log.debug("Ignoring header '{}' that the HTTP client will not set", key);
            }
        });
    }

    /** Builds the body in whatever shape the destination expects. */
    private String buildPayload(Alert alert, MediaType mediaType) throws DeliveryException {
        try {
            return switch (mediaType.getType()) {
                case SLACK -> slackPayload(alert, mediaType);
                case TEAMS -> teamsPayload(alert);
                case PAGERDUTY -> pagerDutyPayload(alert, mediaType);
                case TELEGRAM -> telegramPayload(alert, mediaType);
                // A generic webhook's body is the rendered message, which the
                // template already produced as JSON.
                default -> genericPayload(alert);
            };
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PermanentDeliveryException("could not build the webhook payload: " + e.getMessage(), e);
        }
    }

    private String slackPayload(Alert alert, MediaType mediaType) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("text", alert.getSubject());

        String channel = mediaType.configString("channel", "");
        if (!channel.isBlank()) {
            payload.put("channel", channel);
        }
        String username = mediaType.configString("username", "");
        if (!username.isBlank()) {
            payload.put("username", username);
        }

        // Block Kit rather than the legacy attachments API, which Slack has
        // deprecated and renders inconsistently in newer clients.
        var blocks = payload.putArray("blocks");
        var header = blocks.addObject();
        header.put("type", "section");
        header.putObject("text").put("type", "mrkdwn").put("text", "*" + alert.getSubject() + "*");

        var body = blocks.addObject();
        body.put("type", "section");
        body.putObject("text").put("type", "mrkdwn").put("text", alert.getMessage());

        return JSON.writeValueAsString(payload);
    }

    private String teamsPayload(Alert alert) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("@type", "MessageCard");
        payload.put("@context", "https://schema.org/extensions");
        payload.put("summary", alert.getSubject());
        payload.put("title", alert.getSubject());
        payload.put("text", alert.getMessage().replace("\n", "\n\n"));
        // Severity colour, so a disaster is visibly different from a warning
        // in a channel that is scrolling.
        payload.put("themeColor", severityColour(alert));
        return JSON.writeValueAsString(payload);
    }

    private String pagerDutyPayload(Alert alert, MediaType mediaType)
            throws com.fasterxml.jackson.core.JsonProcessingException, DeliveryException {
        String routingKey = mediaType.configString("routingKey", "");
        if (routingKey.isBlank()) {
            throw new PermanentDeliveryException("PagerDuty media type has no routing key configured");
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("routing_key", routingKey);
        // dedup_key ties the resolve event to the trigger event, so PagerDuty
        // closes the incident it opened instead of leaving it hanging.
        payload.put("dedup_key", "nms-problem-" + (alert.getProblem() == null ? alert.getId()
                : alert.getProblem().getId()));
        payload.put("event_action", isRecovery(alert) ? "resolve" : "trigger");

        ObjectNode details = payload.putObject("payload");
        details.put("summary", alert.getSubject());
        details.put("source", alert.getProblem() != null && alert.getProblem().getHost() != null
                ? alert.getProblem().getHost().getName() : "monitoring");
        details.put("severity", pagerDutySeverity(alert));
        details.putObject("custom_details").put("message", alert.getMessage());

        return JSON.writeValueAsString(payload);
    }

    private String telegramPayload(Alert alert, MediaType mediaType)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("chat_id", alert.getSendTo().isBlank()
                ? mediaType.configString("chatId", "") : alert.getSendTo());
        payload.put("text", alert.getSubject() + "\n\n" + alert.getMessage());
        payload.put("disable_web_page_preview", true);
        return JSON.writeValueAsString(payload);
    }

    private String genericPayload(Alert alert) throws com.fasterxml.jackson.core.JsonProcessingException {
        String message = alert.getMessage().trim();
        // A generic webhook's template usually renders JSON directly; sending
        // it verbatim is what lets it match whatever the receiver expects.
        if (message.startsWith("{") || message.startsWith("[")) {
            try {
                JsonNode parsed = JSON.readTree(message);
                return JSON.writeValueAsString(parsed);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.debug("Webhook template did not produce valid JSON; wrapping it instead");
            }
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("subject", alert.getSubject());
        payload.put("message", alert.getMessage());
        if (alert.getProblem() != null) {
            payload.put("problemId", alert.getProblem().getId());
            payload.put("severity", alert.getProblem().getSeverity().name());
            payload.put("resolved", !alert.getProblem().isOpen());
        }
        return JSON.writeValueAsString(payload);
    }

    private static boolean isRecovery(Alert alert) {
        return alert.getProblem() != null && !alert.getProblem().isOpen();
    }

    private static String pagerDutySeverity(Alert alert) {
        if (alert.getProblem() == null) {
            return "warning";
        }
        return switch (alert.getProblem().getSeverity()) {
            case DISASTER, HIGH -> "critical";
            case AVERAGE -> "error";
            case WARNING -> "warning";
            default -> "info";
        };
    }

    private static String severityColour(Alert alert) {
        if (alert.getProblem() == null) {
            return "808080";
        }
        return switch (alert.getProblem().getSeverity()) {
            case DISASTER -> "AD2E2E";
            case HIGH -> "E45959";
            case AVERAGE -> "FFA059";
            case WARNING -> "FFC859";
            case INFORMATION -> "7499FF";
            case NOT_CLASSIFIED -> "97AAB3";
        };
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return "the webhook endpoint";
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }
}
