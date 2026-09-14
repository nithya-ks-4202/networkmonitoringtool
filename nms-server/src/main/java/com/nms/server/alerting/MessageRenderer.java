package com.nms.server.alerting;

import com.nms.server.domain.ActionOperation;
import com.nms.server.domain.AppUser;
import com.nms.server.domain.Host;
import com.nms.server.domain.InterfaceType;
import com.nms.server.domain.MediaType;
import com.nms.server.domain.Problem;
import com.nms.server.domain.ProblemTag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fills in the message a notification carries.
 *
 * <p>Templates live on the media type rather than the action, so wording is
 * written once per channel: what reads well in an email is not what fits in an
 * SMS or renders correctly in a Slack card. An operation may still override
 * both subject and body when one specific alert needs different words.
 */
@Component
public class MessageRenderer {

    private static final Pattern MACRO = Pattern.compile("\\{([A-Z][A-Z0-9.]*)}");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final String baseUrl;

    public MessageRenderer(@Value("${nms.base-url:http://localhost:3000}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Renders the subject and body for one alert.
     *
     * @param recovery true when this announces the problem ending rather than
     *                 starting, which selects a different template
     */
    public RenderedMessage render(MediaType mediaType, ActionOperation operation,
                                  Problem problem, AppUser recipient, boolean recovery) {
        String templateKey = recovery ? "TRIGGER.RECOVERY" : "TRIGGER.PROBLEM";
        Map<String, String> template = mediaType.template(templateKey);

        String subject = operation != null && operation.isCustomMessage() && !operation.getSubject().isBlank()
                ? operation.getSubject()
                : template.getOrDefault("subject", defaultSubject(recovery));

        String body = operation != null && operation.isCustomMessage() && !operation.getMessage().isBlank()
                ? operation.getMessage()
                : template.getOrDefault("body", defaultBody(recovery));

        Map<String, String> values = macroValues(problem, recipient, recovery);
        return new RenderedMessage(expand(subject, values), expand(body, values));
    }

    /** Substitutes every {@code {MACRO}} the template references. */
    private String expand(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = MACRO.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            // An unknown macro is replaced with an empty string rather than
            // left verbatim: a notification reading "Host: {HOST.NAME}" looks
            // like a broken product, and unlike a poller parameter there is
            // nothing here that can act on the raw text.
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private Map<String, String> macroValues(Problem problem, AppUser recipient, boolean recovery) {
        Map<String, String> values = new HashMap<>();
        Host host = problem.getHost();

        values.put("EVENT.ID", String.valueOf(problem.getId()));
        values.put("EVENT.NAME", problem.getName());
        values.put("EVENT.SEVERITY", problem.getSeverity().name());
        values.put("EVENT.STATUS", recovery ? "RESOLVED" : "PROBLEM");
        values.put("EVENT.TIME", TIME.format(problem.getClock()));
        values.put("EVENT.DATE", DATE.format(problem.getClock()));
        values.put("EVENT.AGE", humanDuration(problem.duration()));
        values.put("EVENT.DURATION", humanDuration(problem.duration()));
        values.put("EVENT.OPDATA", problem.getOpdata());
        values.put("EVENT.ACK.STATUS", problem.isAcknowledged() ? "Yes" : "No");
        values.put("EVENT.TAGS", problem.getTags().stream()
                .map(MessageRenderer::formatTag)
                .collect(Collectors.joining(", ")));
        values.put("EVENT.URL", baseUrl + "/problems/" + problem.getId());
        values.put("TRIGGER.ID", String.valueOf(problem.getObjectId()));

        if (problem.getResolvedAt() != null) {
            values.put("EVENT.RECOVERY.TIME", TIME.format(problem.getResolvedAt()));
            values.put("EVENT.RECOVERY.DATE", DATE.format(problem.getResolvedAt()));
        }

        if (host != null) {
            values.put("HOST.NAME", host.getName());
            values.put("HOST.HOST", host.getTechnicalName());
            values.put("HOST.ID", String.valueOf(host.getId()));
            values.put("HOST.DESCRIPTION", host.getDescription());
            values.put("HOST.CLASS", host.getHostClass().name());
            values.put("HOST.IP", host.connectionAddress(InterfaceType.AGENT).orElse(""));
            values.put("HOST.CONN", host.connectionAddress(InterfaceType.AGENT).orElse(""));
            values.put("HOST.URL", baseUrl + "/hosts/" + host.getId());
            values.put("HOST.GROUPS", host.getGroups().stream()
                    .map(com.nms.server.domain.HostGroup::getName)
                    .collect(Collectors.joining(", ")));
        }

        if (recipient != null) {
            values.put("USER.FULLNAME", recipient.displayName());
            values.put("USER.USERNAME", recipient.getUsername());
        }

        return values;
    }

    private static String formatTag(ProblemTag tag) {
        return tag.getValue().isEmpty() ? tag.getTag() : tag.getTag() + ": " + tag.getValue();
    }

    /** Renders a duration the way an operator would say it out loud. */
    static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
        return (seconds / 86_400) + "d " + ((seconds % 86_400) / 3600) + "h";
    }

    private static String defaultSubject(boolean recovery) {
        return recovery ? "[RESOLVED] {EVENT.NAME}" : "[{EVENT.SEVERITY}] {EVENT.NAME}";
    }

    private static String defaultBody(boolean recovery) {
        return recovery
                ? """
                  Problem resolved: {EVENT.NAME}
                  Host: {HOST.NAME} ({HOST.IP})
                  Duration: {EVENT.DURATION}
                  {EVENT.URL}
                  """
                : """
                  Problem started: {EVENT.NAME}
                  Host: {HOST.NAME} ({HOST.IP})
                  Severity: {EVENT.SEVERITY}
                  Started at: {EVENT.TIME} on {EVENT.DATE}
                  {EVENT.OPDATA}
                  {EVENT.URL}
                  """;
    }

    /** A rendered notification. */
    public record RenderedMessage(String subject, String body) {
    }
}
