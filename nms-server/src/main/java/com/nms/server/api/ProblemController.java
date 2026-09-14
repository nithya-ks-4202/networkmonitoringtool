package com.nms.server.api;

import com.nms.common.Severity;
import com.nms.server.domain.Problem;
import com.nms.server.domain.ProblemAck;
import com.nms.server.repository.CoreRepositories.AppUserRepository;
import com.nms.server.repository.ProblemRepository;
import com.nms.server.security.AuthenticatedUser;
import com.nms.server.trigger.ProblemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/problems")
@Tag(name = "Problems", description = "Current and historical incidents")
public class ProblemController {

    private final ProblemService problemService;
    private final ProblemRepository problems;
    private final AppUserRepository users;

    public ProblemController(ProblemService problemService,
                             ProblemRepository problems,
                             AppUserRepository users) {
        this.problemService = problemService;
        this.problems = problems;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('problem.read')")
    @Operation(summary = "List open problems, most severe first")
    @Transactional(readOnly = true)
    public List<ProblemView> list(
            @RequestParam(required = false, defaultValue = "NOT_CLASSIFIED") Severity minSeverity,
            @RequestParam(required = false, defaultValue = "200") int limit) {

        return problemService.openProblems(AuthenticatedUser.currentTenantId(), minSeverity,
                        Math.min(limit, 1000)).stream()
                .map(ProblemView::from)
                .toList();
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('problem.read')")
    @Operation(summary = "Count open problems by severity")
    @Transactional(readOnly = true)
    public Map<Severity, Long> summary() {
        // Every severity is present in the response even at zero, so a
        // dashboard tile does not have to invent the missing keys and can
        // render a stable set of counters.
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0L);
        }
        for (Object[] row : problems.countOpenBySeverity(AuthenticatedUser.currentTenantId())) {
            counts.put((Severity) row[0], (Long) row[1]);
        }
        return counts;
    }

    @PostMapping("/{problemId}/acknowledge")
    @PreAuthorize("hasAuthority('problem.acknowledge')")
    @Operation(summary = "Acknowledge, comment on, re-prioritise, suppress or close a problem")
    @Transactional
    public ResponseEntity<ProblemView> acknowledge(@PathVariable Long problemId,
                                                   @Valid @RequestBody AcknowledgeRequest request) {
        Long tenantId = AuthenticatedUser.currentTenantId();
        Problem problem = problems.findById(problemId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElse(null);
        if (problem == null) {
            return ResponseEntity.notFound().build();
        }

        ProblemAck action = new ProblemAck();
        AuthenticatedUser.current()
                .flatMap(caller -> users.findById(caller.userId()))
                .ifPresent(action::setUser);

        action.setMessage(request.message() == null ? "" : request.message());
        action.setAcknowledge(request.acknowledge());
        action.setUnacknowledge(request.unacknowledge());
        action.setClose(request.close());
        action.setComment(request.message() != null && !request.message().isBlank());
        action.setChangeSeverity(request.newSeverity() != null);
        action.setNewSeverity(request.newSeverity());

        if (request.suppressForMinutes() != null && request.suppressForMinutes() > 0) {
            action.setSuppress(true);
            action.setSuppressUntil(Instant.now().plus(Duration.ofMinutes(request.suppressForMinutes())));
        }

        problemService.acknowledge(problem, action);
        return ResponseEntity.ok(ProblemView.from(problem));
    }

    /** A problem as the interface shows it. */
    public record ProblemView(
            Long id,
            String name,
            Severity severity,
            Severity originalSeverity,
            Instant startedAt,
            String duration,
            boolean acknowledged,
            boolean suppressed,
            boolean resolved,
            Instant resolvedAt,
            Long hostId,
            String hostName,
            String opdata,
            List<String> tags,
            int updateCount) {

        static ProblemView from(Problem problem) {
            return new ProblemView(
                    problem.getId(),
                    problem.getName(),
                    problem.getSeverity(),
                    problem.getOriginalSeverity(),
                    problem.getClock(),
                    com.nms.server.util.Durations.human(problem.duration()),
                    problem.isAcknowledged(),
                    problem.isSuppressed(),
                    !problem.isOpen(),
                    problem.getResolvedAt(),
                    problem.getHost() == null ? null : problem.getHost().getId(),
                    problem.getHost() == null ? "" : problem.getHost().getName(),
                    problem.getOpdata(),
                    problem.getTags().stream()
                            .map(tag -> tag.getValue().isEmpty()
                                    ? tag.getTag() : tag.getTag() + ": " + tag.getValue())
                            .toList(),
                    problem.getAcknowledgements().size());
        }
    }

    /** An operator action on a problem. */
    public record AcknowledgeRequest(
            boolean acknowledge,
            boolean unacknowledge,
            boolean close,
            String message,
            Severity newSeverity,
            Integer suppressForMinutes) {
    }
}
