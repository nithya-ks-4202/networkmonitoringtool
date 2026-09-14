package com.nms.server.trigger;

import com.nms.common.Severity;
import com.nms.server.alerting.EscalationService;
import com.nms.server.domain.Event;
import com.nms.server.domain.EventGeneration;
import com.nms.server.domain.EventObjectType;
import com.nms.server.domain.EventSource;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostTag;
import com.nms.server.domain.MaintenanceStatus;
import com.nms.server.domain.Problem;
import com.nms.server.domain.ProblemAck;
import com.nms.server.domain.ProblemTag;
import com.nms.server.domain.TriggerDef;
import com.nms.server.domain.TriggerTag;
import com.nms.server.domain.TriggerValue;
import com.nms.server.repository.EventRepository;
import com.nms.server.repository.ProblemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Opens, closes and annotates problems.
 *
 * <p>Two records are kept for every state change. The {@link Event} is an
 * immutable fact -- "this trigger went to PROBLEM at 09:14" -- and is never
 * modified. The {@link Problem} is the mutable incident built from it. Keeping
 * them apart is what lets the problem table stay small enough to answer "what
 * is broken right now" instantly while events accumulate indefinitely for
 * reporting.
 */
@Service
public class ProblemService {

    private static final Logger log = LoggerFactory.getLogger(ProblemService.class);

    private final ProblemRepository problems;
    private final EventRepository events;
    private final EscalationService escalationService;

    public ProblemService(ProblemRepository problems,
                          EventRepository events,
                          EscalationService escalationService) {
        this.problems = problems;
        this.events = events;
        this.escalationService = escalationService;
    }

    /**
     * Raises a problem for a trigger that has just gone to PROBLEM.
     *
     * @return the new problem, or empty when one was already open and the
     *         trigger is configured to hold only one at a time
     */
    @Transactional
    public Optional<Problem> openProblem(TriggerDef trigger, Instant clock) {
        if (trigger.getEventGeneration() == EventGeneration.SINGLE) {
            Optional<Problem> existing = problems.findOpenByTriggerId(trigger.getId());
            if (existing.isPresent()) {
                // The trigger is already reporting this. Raising a second one
                // would double every page for a single ongoing fault.
                return Optional.empty();
            }
        }

        Host host = trigger.getHost();
        Event event = new Event();
        event.setTenantId(trigger.getTenantId());
        event.setSource(EventSource.TRIGGER);
        event.setObjectType(EventObjectType.TRIGGER);
        event.setObjectId(trigger.getId());
        event.setClock(clock);
        event.setValue(TriggerValue.PROBLEM);
        event.setSeverity(trigger.getSeverity());
        event.setName(trigger.problemName());
        events.save(event);

        Problem problem = new Problem();
        problem.setTenantId(trigger.getTenantId());
        problem.setEvent(event);
        problem.setSource(EventSource.TRIGGER);
        problem.setObjectType(EventObjectType.TRIGGER);
        problem.setObjectId(trigger.getId());
        problem.setHost(host);
        problem.setClock(clock);
        problem.setName(trigger.problemName());
        problem.setSeverity(trigger.getSeverity());
        problem.setOriginalSeverity(trigger.getSeverity());
        problem.setOpdata(trigger.getOpdata());

        // Suppressed rather than discarded during maintenance. A fault that
        // begins inside a maintenance window is still a fault, and it has to
        // become visible the moment the window closes.
        boolean inMaintenance = host != null
                && host.getMaintenanceStatus() == MaintenanceStatus.IN_PROGRESS;
        problem.setSuppressed(inMaintenance);

        copyTags(trigger, host, problem);
        problems.save(problem);

        log.info("Problem opened: [{}] {} on {}",
                trigger.getSeverity(), problem.getName(),
                host == null ? "(no host)" : host.getName());

        // Escalation is started even for a suppressed problem; the escalator
        // holds it in PAUSED_MAINTENANCE rather than never learning about it.
        escalationService.onProblemOpened(problem);
        return Optional.of(problem);
    }

    /**
     * Tags are copied onto the problem rather than referenced.
     *
     * <p>Re-tagging a host later must not silently rewrite how problems that
     * already happened were routed, and a post-incident review has to see the
     * tags as they were at the time.
     */
    private void copyTags(TriggerDef trigger, Host host, Problem problem) {
        for (TriggerTag tag : trigger.getTags()) {
            ProblemTag copy = new ProblemTag();
            copy.setProblem(problem);
            copy.setTag(tag.getTag());
            copy.setValue(tag.getValue());
            problem.getTags().add(copy);
        }
        if (host != null) {
            for (HostTag tag : host.getTags()) {
                ProblemTag copy = new ProblemTag();
                copy.setProblem(problem);
                copy.setTag(tag.getTag());
                copy.setValue(tag.getValue());
                problem.getTags().add(copy);
            }
        }
    }

    /** Closes every open problem for a trigger that has returned to OK. */
    @Transactional
    public void resolveProblems(TriggerDef trigger, Instant clock) {
        List<Problem> open = problems.findAllOpenByTriggerId(trigger.getId());
        if (open.isEmpty()) {
            return;
        }

        Event recovery = new Event();
        recovery.setTenantId(trigger.getTenantId());
        recovery.setSource(EventSource.TRIGGER);
        recovery.setObjectType(EventObjectType.TRIGGER);
        recovery.setObjectId(trigger.getId());
        recovery.setClock(clock);
        recovery.setValue(TriggerValue.OK);
        recovery.setSeverity(trigger.getSeverity());
        recovery.setName(trigger.problemName());
        events.save(recovery);

        for (Problem problem : open) {
            problem.setRecoveryEvent(recovery);
            problem.setResolvedAt(clock);
            problem.setResolvedBy("system");
            problems.save(problem);

            log.info("Problem resolved after {}: {} on {}",
                    problem.duration(), problem.getName(),
                    problem.getHost() == null ? "(no host)" : problem.getHost().getName());

            escalationService.onProblemResolved(problem, recovery);
        }
    }

    /**
     * Records an operator action on a problem.
     *
     * <p>Actions are appended as a timeline rather than folded into flags on
     * the problem, because "who acknowledged this, when, and what did they say"
     * is the question every post-incident review starts with.
     */
    @Transactional
    public ProblemAck acknowledge(Problem problem, ProblemAck action) {
        action.setProblem(problem);
        action.setClock(Instant.now());
        problem.getAcknowledgements().add(action);

        if (action.isAcknowledge()) {
            problem.setAcknowledged(true);
        }
        if (action.isUnacknowledge()) {
            problem.setAcknowledged(false);
        }
        if (action.isChangeSeverity() && action.getNewSeverity() != null) {
            // The original severity stays untouched, so reporting is not
            // rewritten by a triage decision taken after the fact.
            problem.setSeverity(action.getNewSeverity());
        }
        if (action.isSuppress()) {
            problem.setSuppressed(true);
            problem.setSuppressedUntil(action.getSuppressUntil());
        }
        if (action.isClose()) {
            closeManually(problem, action);
        }

        problems.save(problem);

        if (action.isAcknowledge()) {
            escalationService.onProblemAcknowledged(problem);
        }
        return action;
    }

    private void closeManually(Problem problem, ProblemAck action) {
        Event recovery = new Event();
        recovery.setTenantId(problem.getTenantId());
        recovery.setSource(EventSource.TRIGGER);
        recovery.setObjectType(EventObjectType.TRIGGER);
        recovery.setObjectId(problem.getObjectId());
        recovery.setClock(Instant.now());
        recovery.setValue(TriggerValue.OK);
        recovery.setSeverity(problem.getSeverity());
        recovery.setName(problem.getName());
        events.save(recovery);

        problem.setRecoveryEvent(recovery);
        problem.setResolvedAt(recovery.getClock());
        problem.setResolvedBy(action.getUser() == null ? "operator" : action.getUser().getUsername());

        escalationService.onProblemResolved(problem, recovery);
    }

    /** Marks problems as suppressed or not, following a maintenance change. */
    @Transactional
    public void setSuppressed(List<Long> hostIds, boolean suppressed) {
        if (hostIds.isEmpty()) {
            return;
        }
        for (Problem problem : problems.findOpenForHosts(hostIds)) {
            if (problem.isSuppressed() != suppressed) {
                problem.setSuppressed(suppressed);
                problems.save(problem);
            }
        }
    }

    /** Lifts operator suppressions that have run out. */
    @Transactional
    public int releaseExpiredSuppressions(Instant now) {
        List<Problem> expired = problems.findExpiredSuppressions(now);
        for (Problem problem : expired) {
            problem.setSuppressed(false);
            problem.setSuppressedUntil(null);
            problems.save(problem);
            // The problem is visible again, so escalation resumes from the
            // rung it had reached rather than starting over.
            escalationService.onSuppressionLifted(problem);
        }
        return expired.size();
    }

    /** Open problems for a tenant, most severe first. */
    @Transactional(readOnly = true)
    public List<Problem> openProblems(Long tenantId, Severity minSeverity, int limit) {
        return problems.findOpen(tenantId, minSeverity,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
