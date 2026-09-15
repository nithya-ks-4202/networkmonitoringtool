package com.nms.server.alerting;

import com.nms.server.domain.Escalation;
import com.nms.server.repository.CoreRepositories.EscalationRepository;
import com.nms.server.trigger.ProblemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Advances escalations whose next rung has come due.
 *
 * <p>This is the clock behind "tell them again in thirty minutes, then widen to
 * the team". Without it an escalation ladder would only ever run its first
 * rung, which is the same as having no ladder.
 */
@Component
@ConditionalOnProperty(name = "nms.escalator.enabled", havingValue = "true", matchIfMissing = true)
public class EscalationRunner {

    private static final Logger log = LoggerFactory.getLogger(EscalationRunner.class);

    private final EscalationRepository escalations;
    private final EscalationService escalationService;
    private final ProblemService problemService;
    private final int batchSize;

    public EscalationRunner(EscalationRepository escalations,
                            EscalationService escalationService,
                            ProblemService problemService,
                            @Value("${nms.escalator.batch-size:200}") int batchSize) {
        this.escalations = escalations;
        this.escalationService = escalationService;
        this.problemService = problemService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${nms.escalator.interval-ms:10000}")
    public void run() {
        try {
            Instant now = Instant.now();

            // Suppressions are lifted first, so an escalation released this
            // pass advances in the same pass rather than waiting for the next.
            int released = problemService.releaseExpiredSuppressions(now);
            if (released > 0) {
                log.info("Lifted {} expired problem suppression(s)", released);
            }

            List<Long> due = escalations.findDueIds(now, PageRequest.of(0, batchSize));
            for (Long escalationId : due) {
                try {
                    escalationService.advance(escalationId);
                } catch (RuntimeException e) {
                    // One broken escalation must not stall every other
                    // incident's notifications.
                    log.error("Failed to advance escalation {}: {}",
                            escalationId, e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Escalation pass failed: {}", e.getMessage(), e);
        }
    }
}
