package com.nms.server.alerting;

import com.nms.common.Severity;
import com.nms.server.domain.Action;
import com.nms.server.domain.ActionCondition;
import com.nms.server.domain.ConditionEvaluation;
import com.nms.server.domain.ConditionOperator;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostGroup;
import com.nms.server.domain.Problem;
import com.nms.server.domain.ProblemTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a problem matches an action's conditions.
 *
 * <p>The default combination rule is the one operators almost always mean:
 * conditions of the same type are OR-ed, conditions of different types are
 * AND-ed. "Host group is A or B, and severity is at least High" reads naturally
 * and needs no formula. A custom formula is available for the rest.
 */
@Component
public class ActionMatcher {

    private static final Logger log = LoggerFactory.getLogger(ActionMatcher.class);

    /** True when this action should run for this problem. */
    public boolean matches(Action action, Problem problem) {
        List<ActionCondition> conditions = action.getConditions();
        if (conditions.isEmpty()) {
            // No conditions means every event of this source. Deliberate, and
            // the way a catch-all "notify on anything" action is written.
            return true;
        }

        Map<ActionCondition, Boolean> results = new LinkedHashMap<>();
        for (ActionCondition condition : conditions) {
            results.put(condition, evaluate(condition, problem));
        }

        return switch (action.getEvalType()) {
            case AND -> results.values().stream().allMatch(Boolean::booleanValue);
            case OR -> results.values().stream().anyMatch(Boolean::booleanValue);
            case AND_OR -> andOr(results);
            case CUSTOM -> customFormula(action, results);
        };
    }

    /** Same-type conditions OR-ed, different-type groups AND-ed together. */
    private boolean andOr(Map<ActionCondition, Boolean> results) {
        Map<String, Boolean> byType = new LinkedHashMap<>();
        results.forEach((condition, matched) ->
                byType.merge(condition.getConditionType().name(), matched, Boolean::logicalOr));
        return byType.values().stream().allMatch(Boolean::booleanValue);
    }

    /** Evaluates a boolean formula over condition labels, e.g. "A and (B or C)". */
    private boolean customFormula(Action action, Map<ActionCondition, Boolean> results) {
        Map<String, Boolean> byLabel = new LinkedHashMap<>();
        results.forEach((condition, matched) -> byLabel.put(condition.getLabel(), matched));

        try {
            return new BooleanFormulaEvaluator(action.getFormula(), byLabel).evaluate();
        } catch (RuntimeException e) {
            // A malformed formula must not fire the action for everything.
            // Failing closed means an alert may be missed; failing open would
            // page everyone for every event, which is worse and harder to spot.
            log.error("Action '{}' has an invalid condition formula '{}': {}. "
                            + "The action will not run until it is corrected.",
                    action.getName(), action.getFormula(), e.getMessage());
            return false;
        }
    }

    private boolean evaluate(ActionCondition condition, Problem problem) {
        Host host = problem.getHost();

        return switch (condition.getConditionType()) {
            case TRIGGER_SEVERITY -> compareSeverity(condition, problem.getSeverity());

            case TRIGGER_NAME -> compareText(condition, problem.getName());

            case HOST -> host != null && compareId(condition, host.getId());

            case HOST_GROUP -> host != null && host.getGroups().stream()
                    .anyMatch(group -> compareId(condition, group.getId())
                            || compareText(condition, group.getName()));

            case HOST_CLASS -> host != null
                    && compareText(condition, host.getHostClass().name());

            case HOST_TEMPLATE -> host != null && host.getTemplates().stream()
                    .anyMatch(template -> compareId(condition, template.getId())
                            || compareText(condition, template.getTechnicalName()));

            case TRIGGER -> compareId(condition, problem.getObjectId());

            case PROXY -> host != null && host.getProxy() != null
                    && compareId(condition, host.getProxy().getId());

            case PROBLEM_SUPPRESSED -> condition.getOperator() == ConditionOperator.YES
                    ? problem.isSuppressed()
                    : !problem.isSuppressed();

            case EVENT_TAG -> problem.getTags().stream()
                    .anyMatch(tag -> compareText(condition, tag.getTag()));

            // value holds the tag name, value2 the tag value, so this asks
            // "does tag X have value Y" rather than "does any tag match".
            case EVENT_TAG_VALUE -> problem.getTags().stream()
                    .filter(tag -> tag.getTag().equals(condition.getValue()))
                    .anyMatch(tag -> compareTagValue(condition, tag));

            case TIME_PERIOD -> withinTimePeriod(condition.getValue())
                    == (condition.getOperator() != ConditionOperator.NOT_IN);
        };
    }

    private boolean compareSeverity(ActionCondition condition, Severity severity) {
        Severity threshold;
        try {
            threshold = Severity.valueOf(condition.getValue().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            try {
                threshold = Severity.ofLevel(Integer.parseInt(condition.getValue().trim()));
            } catch (RuntimeException inner) {
                log.warn("Action condition has an unrecognised severity '{}'", condition.getValue());
                return false;
            }
        }

        return switch (condition.getOperator()) {
            case EQUALS -> severity == threshold;
            case NOT_EQUALS -> severity != threshold;
            case GREATER_OR_EQUAL -> severity.atLeast(threshold);
            case LESS_OR_EQUAL -> threshold.atLeast(severity);
            default -> false;
        };
    }

    private boolean compareText(ActionCondition condition, String actual) {
        String expected = condition.getValue();
        if (actual == null) {
            return false;
        }

        return switch (condition.getOperator()) {
            case EQUALS -> actual.equals(expected);
            case NOT_EQUALS -> !actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case NOT_CONTAINS -> !actual.contains(expected);
            case MATCHES -> regexMatches(expected, actual);
            case NOT_MATCHES -> !regexMatches(expected, actual);
            default -> false;
        };
    }

    private boolean compareTagValue(ActionCondition condition, ProblemTag tag) {
        String expected = condition.getValue2();
        String actual = tag.getValue();

        return switch (condition.getOperator()) {
            case EQUALS -> actual.equals(expected);
            case NOT_EQUALS -> !actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case NOT_CONTAINS -> !actual.contains(expected);
            case MATCHES -> regexMatches(expected, actual);
            case NOT_MATCHES -> !regexMatches(expected, actual);
            default -> false;
        };
    }

    /** Matches an id against a condition holding one or several ids. */
    private boolean compareId(ActionCondition condition, Long actual) {
        if (actual == null) {
            return false;
        }
        boolean present = false;
        for (String part : condition.getValue().split(",")) {
            if (part.trim().equals(actual.toString())) {
                present = true;
                break;
            }
        }
        return switch (condition.getOperator()) {
            case NOT_EQUALS, NOT_IN -> !present;
            default -> present;
        };
    }

    private static boolean regexMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException e) {
            log.warn("Action condition has an invalid regular expression '{}': {}",
                    pattern, e.getDescription());
            return false;
        }
    }

    /**
     * Whether now falls inside a period specification such as
     * {@code 1-5,09:00-18:00}, with Monday as day 1.
     *
     * <p>Used to route out-of-hours problems differently from working-hours
     * ones, which is usually the difference between an email and a phone call.
     */
    static boolean withinTimePeriod(String specification) {
        if (specification == null || specification.isBlank()) {
            return true;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int today = now.getDayOfWeek().getValue();
        int minutesNow = now.getHour() * 60 + now.getMinute();

        for (String period : specification.split(";")) {
            if (matchesPeriod(period.trim(), today, minutesNow)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPeriod(String period, int today, int minutesNow) {
        String[] parts = period.split(",");
        if (parts.length != 2) {
            return false;
        }

        if (!dayMatches(parts[0].trim(), today)) {
            return false;
        }

        String[] times = parts[1].split("-");
        if (times.length != 2) {
            return false;
        }
        Integer from = parseMinutes(times[0]);
        Integer to = parseMinutes(times[1]);
        if (from == null || to == null) {
            return false;
        }
        // The end is exclusive, so 09:00-18:00 and 18:00-24:00 tile the day
        // without a one-minute overlap at the boundary.
        return minutesNow >= from && minutesNow < to;
    }

    private static boolean dayMatches(String specification, int today) {
        for (String range : specification.split("\\|")) {
            String trimmed = range.trim();
            int dash = trimmed.indexOf('-');
            try {
                if (dash > 0) {
                    int from = Integer.parseInt(trimmed.substring(0, dash).trim());
                    int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
                    if (today >= from && today <= to) {
                        return true;
                    }
                } else if (Integer.parseInt(trimmed) == today) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static Integer parseMinutes(String time) {
        String[] parts = time.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Day names, for rendering a period back to an operator. */
    static List<String> dayNames() {
        List<String> names = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            names.add(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH));
        }
        return names;
    }

    /** Host groups a problem's host belongs to, for diagnostics. */
    static List<String> groupNames(Host host) {
        return host == null ? List.of()
                : host.getGroups().stream().map(HostGroup::getName).toList();
    }
}
