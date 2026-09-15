package com.nms.server.alerting;

import com.nms.server.domain.Action;
import com.nms.server.domain.ActionOperation;
import com.nms.server.domain.Alert;
import com.nms.server.domain.AlertStatus;
import com.nms.server.domain.AlertType;
import com.nms.server.domain.AppUser;
import com.nms.server.domain.Escalation;
import com.nms.server.domain.EscalationStatus;
import com.nms.server.domain.Event;
import com.nms.server.domain.EventSource;
import com.nms.server.domain.MaintenanceStatus;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.OperationType;
import com.nms.server.domain.Problem;
import com.nms.server.domain.UserMedia;
import com.nms.server.repository.CoreRepositories.ActionRepository;
import com.nms.server.repository.CoreRepositories.AlertRepository;
import com.nms.server.repository.CoreRepositories.AppUserRepository;
import com.nms.server.repository.CoreRepositories.EscalationRepository;
import com.nms.server.repository.CoreRepositories.UserMediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the escalation ladders attached to a problem.
 *
 * <p>An escalation is a small state machine per (action, problem) pair: notify,
 * wait, notify again, widen. It exists so that an unanswered alert becomes
 * somebody else's alert rather than being sent once into an empty room.
 *
 * <p>Alerts are persisted before delivery is attempted. A queued row that is
 * never marked sent survives a crash and is retried; a message sent from memory
 * is simply lost, and nobody finds out until the post-incident review.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final ActionRepository actions;
    private final EscalationRepository escalations;
    private final AlertRepository alerts;
    private final AppUserRepository users;
    private final UserMediaRepository userMedia;
    private final ActionMatcher actionMatcher;
    private final MessageRenderer messageRenderer;

    public EscalationService(ActionRepository actions,
                             EscalationRepository escalations,
                             AlertRepository alerts,
                             AppUserRepository users,
                             UserMediaRepository userMedia,
                             ActionMatcher actionMatcher,
                             MessageRenderer messageRenderer) {
        this.actions = actions;
        this.escalations = escalations;
        this.alerts = alerts;
        this.users = users;
        this.userMedia = userMedia;
        this.actionMatcher = actionMatcher;
        this.messageRenderer = messageRenderer;
    }

    /** Starts an escalation for every action whose conditions the problem meets. */
    @Transactional
    public void onProblemOpened(Problem problem) {
        List<Action> candidates = actions.findEnabledBySource(problem.getTenantId(), EventSource.TRIGGER);

        for (Action action : candidates) {
            if (!actionMatcher.matches(action, problem)) {
                continue;
            }
            try {
                startEscalation(action, problem);
            } catch (DataIntegrityViolationException e) {
                // Another server instance handling the same event won the race
                // to insert. The unique constraint on (action, problem) is what
                // makes that safe, and the loser has nothing left to do.
                log.debug("Escalation for action {} on problem {} already exists",
                        action.getId(), problem.getId());
            }
        }
    }

    private void startEscalation(Action action, Problem problem) {
        Escalation escalation = new Escalation();
        escalation.setTenantId(problem.getTenantId());
        escalation.setAction(action);
        escalation.setProblem(problem);
        escalation.setTriggerId(problem.getObjectId());
        escalation.setHost(problem.getHost());
        escalation.setStep(0);

        boolean inMaintenance = problem.getHost() != null
                && problem.getHost().getMaintenanceStatus() == MaintenanceStatus.IN_PROGRESS;

        if (inMaintenance && action.isPauseDuringMaintenance()) {
            // Paused, not cancelled. When the window ends the ladder resumes
            // from the top, so a fault that began during planned work is still
            // escalated rather than quietly forgotten.
            escalation.setStatus(EscalationStatus.PAUSED_MAINTENANCE);
            escalation.setNextRunAt(Instant.now().plusSeconds(60));
        } else {
            escalation.setStatus(EscalationStatus.ACTIVE);
            // Due immediately: the first rung of an escalation is the initial
            // notification, and delaying it would delay every alert.
            escalation.setNextRunAt(Instant.now());
        }

        escalations.save(escalation);
        log.debug("Escalation started for action '{}' on problem {}", action.getName(), problem.getId());
    }

    /**
     * Runs one rung of an escalation.
     *
     * <p>Takes an identifier rather than an entity, and loads it here. The
     * runner selects due escalations outside a transaction; an entity handed
     * across that boundary is detached, and the first lazy association touched
     * -- {@code getProblem()} -- throws. Loading inside the transaction that
     * uses it is what makes every association resolvable, and it also means
     * the state acted on is current rather than whatever it was when the
     * selection ran.
     *
     * @return true when the escalation still has work to do
     */
    @Transactional
    public boolean advance(Long escalationId) {
        Escalation escalation = escalations.findById(escalationId).orElse(null);
        if (escalation == null) {
            // Resolved and cleaned up between the selection and now. Not an
            // error: the work it represented is done.
            return false;
        }

        Problem problem = escalation.getProblem();
        Action action = actions.findByIdWithOperations(escalation.getAction().getId())
                .orElse(null);

        if (action == null || !action.isEnabled()) {
            escalation.setStatus(EscalationStatus.CANCELLED);
            escalations.save(escalation);
            return false;
        }

        if (!problem.isOpen()) {
            escalation.setStatus(EscalationStatus.COMPLETED);
            escalations.save(escalation);
            return false;
        }

        if (shouldPause(escalation, action, problem)) {
            return true;
        }

        int step = escalation.getStep() + 1;
        List<ActionOperation> rung = action.getOperations().stream()
                .filter(operation -> operation.appliesToStep(step))
                .toList();

        if (rung.isEmpty()) {
            // Past the end of the ladder. Everyone who was going to be told
            // has been told, and repeating forever would be noise.
            escalation.setStatus(EscalationStatus.COMPLETED);
            escalations.save(escalation);
            log.debug("Escalation {} completed after {} steps", escalation.getId(), step - 1);
            return false;
        }

        for (ActionOperation operation : rung) {
            executeOperation(operation, escalation, problem, step, false);
        }

        escalation.setStep(step);
        escalation.setNextRunAt(Instant.now().plusSeconds(stepDuration(action, rung)));
        escalations.save(escalation);
        return true;
    }

    /** Whether this escalation should hold rather than climb. */
    private boolean shouldPause(Escalation escalation, Action action, Problem problem) {
        boolean inMaintenance = problem.getHost() != null
                && problem.getHost().getMaintenanceStatus() == MaintenanceStatus.IN_PROGRESS;

        if (inMaintenance && action.isPauseDuringMaintenance()) {
            escalation.setStatus(EscalationStatus.PAUSED_MAINTENANCE);
            escalation.setNextRunAt(Instant.now().plusSeconds(60));
            escalations.save(escalation);
            return true;
        }

        if (problem.isAcknowledged() && action.isPauseOnAcknowledge()) {
            // Somebody has taken it. Continuing to escalate would page people
            // about a problem that already has an owner, which is exactly the
            // behaviour that teaches operators to ignore alerts.
            escalation.setStatus(EscalationStatus.PAUSED_ACK);
            escalation.setNextRunAt(Instant.now().plusSeconds(300));
            escalations.save(escalation);
            return true;
        }

        if (escalation.getStatus() == EscalationStatus.PAUSED_MAINTENANCE
                || escalation.getStatus() == EscalationStatus.PAUSED_ACK) {
            // The reason for pausing has gone; resume from where it stopped
            // rather than starting the ladder over.
            escalation.setStatus(EscalationStatus.ACTIVE);
        }
        return false;
    }

    /** Seconds until the next rung; an operation may override the action default. */
    private int stepDuration(Action action, List<ActionOperation> rung) {
        return rung.stream()
                .map(ActionOperation::getStepDurationSeconds)
                .filter(duration -> duration > 0)
                .findFirst()
                .orElse(action.getEscalationPeriodSeconds());
    }

    /** Queues the alerts one operation produces. */
    private void executeOperation(ActionOperation operation, Escalation escalation,
                                  Problem problem, int step, boolean recovery) {
        if (operation.getOperationType() != OperationType.SEND_MESSAGE
                && operation.getOperationType() != OperationType.WEBHOOK) {
            // Remote commands and discovery operations are out of scope for
            // this build; queuing an alert for them would promise delivery
            // that never happens.
            log.debug("Operation type {} is not executed in this build", operation.getOperationType());
            return;
        }

        for (AppUser recipient : resolveRecipients(operation)) {
            queueAlertsFor(recipient, operation, escalation, problem, step, recovery);
        }
    }

    /**
     * Expands an operation's targets to individual users.
     *
     * <p>Groups are expanded at send time rather than at configuration time, so
     * an on-call rotation change takes effect on the next alert without editing
     * every escalation ladder that references it.
     */
    private Set<AppUser> resolveRecipients(ActionOperation operation) {
        Set<AppUser> recipients = new LinkedHashSet<>(operation.getUsers());

        List<Long> groupIds = operation.getUserGroups().stream()
                .map(com.nms.server.domain.UserGroup::getId)
                .toList();
        if (!groupIds.isEmpty()) {
            recipients.addAll(users.findEnabledByGroupIds(groupIds));
        }

        // A disabled account, or one in a disabled group, must not be paged:
        // the message would go to somebody who has left.
        recipients.removeIf(user -> !user.isActive());
        return recipients;
    }

    private void queueAlertsFor(AppUser recipient, ActionOperation operation, Escalation escalation,
                                Problem problem, int step, boolean recovery) {
        List<UserMedia> channels = userMedia.findEnabledForUser(recipient.getId());

        List<UserMedia> applicable = new ArrayList<>();
        for (UserMedia channel : channels) {
            // An operation naming a media type means "this channel only";
            // naming none means every channel the recipient has.
            if (operation.getMediaType() != null
                    && !operation.getMediaType().getId().equals(channel.getMediaType().getId())) {
                continue;
            }
            if (!channel.getMediaType().isEnabled()) {
                continue;
            }
            // The severity filter and active period are what make paging
            // survivable: everything by email all day, only a disaster by SMS
            // at three in the morning.
            if (!problem.getSeverity().atLeast(channel.getSeverityFilter())) {
                continue;
            }
            if (!ActionMatcher.withinTimePeriod(channel.getActivePeriod())) {
                continue;
            }
            applicable.add(channel);
        }

        if (applicable.isEmpty()) {
            log.debug("No eligible channel for user {} on problem {}",
                    recipient.getUsername(), problem.getId());
            return;
        }

        for (UserMedia channel : applicable) {
            alerts.save(buildAlert(recipient, channel, operation, escalation, problem, step, recovery));
        }
    }

    private Alert buildAlert(AppUser recipient, UserMedia channel, ActionOperation operation,
                             Escalation escalation, Problem problem, int step, boolean recovery) {
        MediaType mediaType = channel.getMediaType();
        MessageRenderer.RenderedMessage rendered = messageRenderer.render(
                mediaType, operation, problem, recipient, recovery);

        Alert alert = new Alert();
        alert.setTenantId(problem.getTenantId());
        alert.setAction(escalation.getAction());
        alert.setEscalation(escalation);
        alert.setProblem(problem);
        alert.setEvent(recovery ? problem.getRecoveryEvent() : problem.getEvent());
        alert.setUser(recipient);
        alert.setMediaType(mediaType);
        alert.setAlertType(AlertType.MESSAGE);
        alert.setSendTo(channel.getSendTo());
        alert.setSubject(rendered.subject());
        alert.setMessage(rendered.body());
        alert.setStatus(AlertStatus.NEW);
        alert.setEscalationStep(step);
        alert.setScheduledAt(Instant.now());
        return alert;
    }

    /**
     * Handles a problem returning to OK: cancels pending alerts and sends
     * recovery messages to everyone who was told about it.
     */
    @Transactional
    public void onProblemResolved(Problem problem, Event recoveryEvent) {
        // Anything still queued describes a problem that no longer exists.
        // Delivering it would page someone about something already fixed.
        int cancelled = alerts.cancelPendingForProblem(problem.getId(), AlertStatus.CANCELLED);
        if (cancelled > 0) {
            log.debug("Cancelled {} undelivered alerts for resolved problem {}",
                    cancelled, problem.getId());
        }

        for (Escalation escalation : escalations.findActiveByProblemId(problem.getId())) {
            Action action = actions.findByIdWithOperations(escalation.getAction().getId()).orElse(null);

            if (action != null && action.isNotifyOnRecovery() && escalation.getStep() > 0) {
                // Only people who were actually notified get a recovery
                // message. Telling someone a problem is fixed when they never
                // heard it was broken is noise.
                sendRecoveryMessages(action, escalation, problem);
            }

            escalation.setStatus(EscalationStatus.COMPLETED);
            escalations.save(escalation);
        }
    }

    private void sendRecoveryMessages(Action action, Escalation escalation, Problem problem) {
        List<ActionOperation> notified = action.getOperations().stream()
                .filter(operation -> operation.getStepFrom() <= escalation.getStep())
                .toList();

        for (ActionOperation operation : notified) {
            executeOperation(operation, escalation, problem, escalation.getStep(), true);
        }
    }

    /** Pauses escalation when an operator takes ownership. */
    @Transactional
    public void onProblemAcknowledged(Problem problem) {
        for (Escalation escalation : escalations.findActiveByProblemId(problem.getId())) {
            Action action = escalation.getAction();
            if (action != null && action.isPauseOnAcknowledge()) {
                escalation.setStatus(EscalationStatus.PAUSED_ACK);
                escalations.save(escalation);
            }
        }
    }

    /** Resumes escalations held while a problem was suppressed. */
    @Transactional
    public void onSuppressionLifted(Problem problem) {
        for (Escalation escalation : escalations.findActiveByProblemId(problem.getId())) {
            if (escalation.getStatus() == EscalationStatus.PAUSED_MAINTENANCE) {
                escalation.setStatus(EscalationStatus.ACTIVE);
                escalation.setNextRunAt(Instant.now());
                escalations.save(escalation);
            }
        }
    }
}
