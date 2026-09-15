package com.nms.server.alerting;

import com.nms.server.alerting.media.MediaSender;
import com.nms.server.alerting.media.WebhookSender;
import com.nms.server.domain.Alert;
import com.nms.server.domain.AlertStatus;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.MediaTypeKind;
import com.nms.server.repository.CoreRepositories.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Claims alerts and delivers them.
 *
 * <p>Separate from {@link AlertDispatcher} because Spring's transaction support
 * works through a proxy: a {@code @Transactional} method called from another
 * method of the same class runs with no transaction at all. Claiming has to be
 * transactional or two instances could send the same page.
 */
@Service
public class AlertDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AlertDeliveryService.class);

    private final AlertRepository alerts;
    private final Map<MediaTypeKind, MediaSender> senders = new EnumMap<>(MediaTypeKind.class);
    private final WebhookSender webhookSender;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public AlertDeliveryService(AlertRepository alerts,
                                List<MediaSender> availableSenders,
                                WebhookSender webhookSender) {
        this.alerts = alerts;
        this.webhookSender = webhookSender;
        for (MediaSender sender : availableSenders) {
            senders.putIfAbsent(sender.kind(), sender);
        }
        log.info("Alert delivery ready with senders for {}", senders.keySet());
    }

    /**
     * Claims pending alerts and marks them in flight.
     *
     * <p>{@code SKIP LOCKED} lets several instances drain the queue at once
     * without any message being delivered twice -- the property that matters
     * most here, because a duplicate page erodes trust in every alert after it.
     *
     * <p>The status is written inside the claiming transaction, so a crash
     * between claim and delivery leaves a row visibly stuck in SENDING. Such a
     * row is reclaimed once it is older than {@link #STALLED_AFTER}, because
     * an alert nobody will ever retry is a page that was never sent.
     *
     * <p>Returns identifiers rather than entities. The dispatcher calls this
     * outside a transaction and delivers each alert inside its own; an entity
     * crossing that boundary is detached, and the first association touched --
     * {@code getMediaType()} -- throws. Ids make that impossible to get wrong.
     */
    @Transactional
    public List<Long> claim(int batchSize) {
        Instant now = Instant.now();
        List<Alert> pending = alerts.claimPending(now, now.minus(STALLED_AFTER), batchSize);
        pending.forEach(alert -> alert.setStatus(AlertStatus.SENDING));
        alerts.saveAll(pending);
        return pending.stream().map(Alert::getId).toList();
    }

    /**
     * How long an alert may sit in SENDING before another pass reclaims it.
     *
     * <p>Comfortably longer than any sender's timeout, so a slow SMTP server
     * is not mistaken for a dead instance and the message sent twice.
     */
    private static final java.time.Duration STALLED_AFTER = java.time.Duration.ofMinutes(5);

    /** Delivers one alert, recording success or scheduling a retry. */
    @Transactional
    public void deliver(Long alertId) {
        Alert alert = alerts.findById(alertId).orElse(null);
        if (alert == null) {
            // Cancelled or cleaned up since the claim. Nothing to send.
            return;
        }

        MediaType mediaType = alert.getMediaType();
        if (mediaType == null) {
            fail(alert, "the media type has been deleted", false);
            return;
        }
        if (!mediaType.isEnabled()) {
            fail(alert, "media type '" + mediaType.getName() + "' is disabled", false);
            return;
        }

        MediaSender sender = senderFor(mediaType.getType());
        if (sender == null) {
            fail(alert, "no sender is available for " + mediaType.getType(), false);
            return;
        }

        try {
            sender.send(alert, mediaType);
            alert.setStatus(AlertStatus.SENT);
            alert.setSentAt(Instant.now());
            alert.setError("");
            alerts.save(alert);
            sent.incrementAndGet();

        } catch (MediaSender.DeliveryException e) {
            fail(alert, e.getMessage(), e.isRetryable());
        } catch (RuntimeException e) {
            // An unexpected fault is treated as retryable: unknown failures are
            // more often transient than not, and the attempt limit bounds the
            // cost of being wrong about it.
            log.error("Sender for {} threw unexpectedly", mediaType.getType(), e);
            fail(alert, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private void fail(Alert alert, String reason, boolean retryable) {
        MediaType mediaType = alert.getMediaType();
        int maxAttempts = mediaType == null ? 1 : Math.max(1, mediaType.getMaxAttempts());
        alert.setRetries(alert.getRetries() + 1);
        alert.setError(abbreviate(reason));

        if (retryable && alert.getRetries() < maxAttempts) {
            // Exponential back-off, so a provider that is rate limiting is not
            // hammered by the very retries meant to wait for it.
            long delaySeconds = (long) mediaType.getAttemptIntervalSeconds()
                    * (1L << Math.min(4, alert.getRetries() - 1));
            alert.setStatus(AlertStatus.NEW);
            alert.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
            alerts.save(alert);

            log.warn("Alert {} to {} failed ({}); retrying in {}s (attempt {} of {})",
                    alert.getId(), alert.getSendTo(), reason, delaySeconds,
                    alert.getRetries(), maxAttempts);
            return;
        }

        alert.setStatus(AlertStatus.FAILED);
        alerts.save(alert);
        failed.incrementAndGet();

        // Logged at error because an undelivered alert is itself an incident:
        // somebody expected to be told, and was not.
        log.error("Alert {} to {} permanently failed after {} attempt(s): {}",
                alert.getId(), alert.getSendTo(), alert.getRetries(), reason);
    }

    private MediaSender senderFor(MediaTypeKind kind) {
        MediaSender direct = senders.get(kind);
        if (direct != null) {
            return direct;
        }
        // Slack, Teams, PagerDuty and the rest all ride the webhook sender.
        return webhookSender.handles(kind) ? webhookSender : null;
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "delivery failed";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "...";
    }

    public long sentCount() {
        return sent.get();
    }

    public long failedCount() {
        return failed.get();
    }
}
