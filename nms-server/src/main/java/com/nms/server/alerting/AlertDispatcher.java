package com.nms.server.alerting;

import com.nms.server.domain.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives alert delivery on a timer.
 *
 * <p>Holds no transactional logic itself -- that lives in
 * {@link AlertDeliveryService}, so each claim and each delivery gets its own
 * transaction through the proxy. Delivering one alert per transaction means a
 * single failing channel cannot roll back the successful sends beside it.
 */
@Component
@ConditionalOnProperty(name = "nms.alerter.enabled", havingValue = "true", matchIfMissing = true)
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final AlertDeliveryService delivery;
    private final int batchSize;

    public AlertDispatcher(AlertDeliveryService delivery,
                           @Value("${nms.alerter.batch-size:100}") int batchSize) {
        this.delivery = delivery;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${nms.alerter.interval-ms:5000}")
    public void dispatch() {
        try {
            List<Long> pending = delivery.claim(batchSize);
            for (Long alertId : pending) {
                try {
                    delivery.deliver(alertId);
                } catch (RuntimeException e) {
                    // One alert must not abandon the rest of the batch: the
                    // next one might be the disaster-severity page.
                    log.error("Failed to deliver alert {}: {}", alertId, e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Alert dispatch pass failed: {}", e.getMessage(), e);
        }
    }
}
