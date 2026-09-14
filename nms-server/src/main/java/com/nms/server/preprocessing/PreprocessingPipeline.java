package com.nms.server.preprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nms.common.CheckResult;
import com.nms.common.ItemValueType;
import com.nms.server.domain.ItemPreprocessing;
import com.nms.server.domain.PreprocessingErrorHandler;
import com.nms.server.domain.PreprocessingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies an item's preprocessing steps to a raw value.
 *
 * <p>Preprocessing is what makes values from different devices comparable: an
 * SNMP octet counter becomes bits per second, a JSON response becomes a single
 * number, a vendor status string becomes 1 or 0.
 *
 * <p>Step configuration is cached, because it is read for every value collected
 * and changes only when someone edits the item. The cache is short-lived rather
 * than invalidated on write, so an edit takes effect within seconds without
 * needing cross-instance invalidation in a horizontally scaled deployment.
 */
@Component
public class PreprocessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingPipeline.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final PreprocessingConfigLoader configLoader;

    /** itemId to its ordered steps. Empty list for the common no-op case. */
    private final Cache<Long, List<StepConfig>> stepCache = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    /** itemId to its last raw value, backed by item_latest on a miss. */
    private final Cache<Long, RawSample> rawCache = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public PreprocessingPipeline(JdbcTemplate jdbc, PreprocessingConfigLoader configLoader) {
        this.jdbc = jdbc;
        this.configLoader = configLoader;
    }

    /** Runs every configured step, in order, over a successful check result. */
    public PreprocessingResult apply(CheckResult result) {
        List<StepConfig> steps = stepCache.get(result.itemId(), configLoader::loadSteps);

        if (steps.isEmpty()) {
            return PreprocessingResult.stored(result.value(), result.valueType(), result.clock());
        }

        Object value = result.value();
        for (StepConfig step : steps) {
            try {
                StepOutcome outcome = applyStep(step, value, result);
                if (outcome.discarded()) {
                    return PreprocessingResult.discarded(result.clock());
                }
                value = outcome.value();
            } catch (PreprocessingException e) {
                PreprocessingResult handled = handleError(step, e, result);
                if (handled != null) {
                    return handled;
                }
                // SET_VALUE substitutes and carries on with the rest.
                value = step.errorHandlerParams();
            }
        }

        return PreprocessingResult.stored(value, result.valueType(), result.clock());
    }

    private PreprocessingResult handleError(StepConfig step, PreprocessingException e, CheckResult result) {
        return switch (step.errorHandler()) {
            case ERROR -> PreprocessingResult.error(
                    "preprocessing step " + step.step() + " (" + step.type() + "): " + e.getMessage(),
                    result.clock());
            case DISCARD_VALUE -> PreprocessingResult.discarded(result.clock());
            case SET_ERROR -> PreprocessingResult.error(step.errorHandlerParams(), result.clock());
            // Signals the caller to substitute and continue.
            case SET_VALUE -> null;
        };
    }

    private StepOutcome applyStep(StepConfig step, Object value, CheckResult result) {
        return switch (step.type()) {
            case MULTIPLIER -> StepOutcome.of(toDouble(value) * parseDouble(step.param(0, "1")));

            case CHANGE -> {
                RawSample previous = previousRaw(result.itemId());
                double current = toDouble(value);
                storeRaw(result.itemId(), current, result.clock());
                yield previous == null
                        // No baseline yet. Discarding is correct: a "change"
                        // computed against nothing is not a measurement.
                        ? StepOutcome.discard()
                        : StepOutcome.of(current - previous.value());
            }

            case CHANGE_PER_SECOND -> {
                RawSample previous = previousRaw(result.itemId());
                double current = toDouble(value);
                storeRaw(result.itemId(), current, result.clock());
                if (previous == null) {
                    yield StepOutcome.discard();
                }

                double elapsedSeconds =
                        Duration.between(previous.clock(), result.clock()).toMillis() / 1000.0;
                if (elapsedSeconds <= 0) {
                    // Two samples with the same timestamp, usually a duplicate
                    // delivery. Dividing by zero would produce an infinity
                    // that poisons every graph and threshold downstream.
                    yield StepOutcome.discard();
                }

                double delta = current - previous.value();
                if (delta < 0) {
                    // The counter wrapped or the device restarted. There is no
                    // way to tell how much traffic passed in between, and
                    // guessing produces a spike that looks like an incident.
                    yield StepOutcome.discard();
                }
                yield StepOutcome.of(delta / elapsedSeconds);
            }

            case REGEX -> {
                Matcher matcher = Pattern.compile(step.param(0, "(.*)")).matcher(String.valueOf(value));
                if (!matcher.find()) {
                    throw new PreprocessingException(
                            "pattern '" + step.param(0, "") + "' did not match the value");
                }
                String template = step.param(1, "\\1");
                yield StepOutcome.of(expandGroups(template, matcher));
            }

            case TRIM -> StepOutcome.of(trim(String.valueOf(value), step.param(0, " \t\r\n")));

            case JSONPATH -> StepOutcome.of(extractJson(String.valueOf(value), step.param(0, "$")));

            case DISCARD_UNCHANGED -> {
                RawSample previous = previousRaw(result.itemId());
                double current = toDouble(value);
                storeRaw(result.itemId(), current, result.clock());
                yield previous != null && previous.value() == current
                        ? StepOutcome.discard()
                        : StepOutcome.of(value);
            }

            case DISCARD_UNCHANGED_WITH_HEARTBEAT -> {
                RawSample previous = previousRaw(result.itemId());
                double current = toDouble(value);
                long heartbeat = (long) parseDouble(step.param(0, "3600"));
                boolean unchanged = previous != null && previous.value() == current;
                boolean stale = previous == null
                        || Duration.between(previous.clock(), result.clock()).toSeconds() >= heartbeat;
                storeRaw(result.itemId(), current, result.clock());
                // Re-storing periodically stops a flat series developing a gap
                // that a nodata() trigger would read as a collection failure.
                yield unchanged && !stale ? StepOutcome.discard() : StepOutcome.of(value);
            }

            case IN_RANGE -> {
                double numeric = toDouble(value);
                double min = parseDouble(step.param(0, "-Infinity"));
                double max = parseDouble(step.param(1, "Infinity"));
                if (numeric < min || numeric > max) {
                    throw new PreprocessingException(
                            "value " + numeric + " is outside the range " + min + " to " + max);
                }
                yield StepOutcome.of(numeric);
            }

            case BOOL_TO_DECIMAL -> StepOutcome.of(toBoolean(String.valueOf(value)) ? 1L : 0L);

            case HEX_TO_DECIMAL -> {
                try {
                    yield StepOutcome.of(Long.parseLong(
                            String.valueOf(value).trim().replaceFirst("^0[xX]", ""), 16));
                } catch (NumberFormatException e) {
                    throw new PreprocessingException("'" + value + "' is not hexadecimal");
                }
            }

            case OCTAL_TO_DECIMAL -> {
                try {
                    yield StepOutcome.of(Long.parseLong(String.valueOf(value).trim(), 8));
                } catch (NumberFormatException e) {
                    throw new PreprocessingException("'" + value + "' is not octal");
                }
            }

            case CHECK_FOR_ERROR_REGEX -> {
                Matcher matcher = Pattern.compile(step.param(0, "")).matcher(String.valueOf(value));
                if (matcher.find()) {
                    throw new PreprocessingException(
                            "value matched the error pattern: " + matcher.group());
                }
                yield StepOutcome.of(value);
            }

            case CUSTOM_FORMULA -> StepOutcome.of(
                    new ArithmeticEvaluator(step.param(0, "x"), toDouble(value)).evaluate());

            // Applied at display time rather than at storage, so the raw value
            // stays available for arithmetic and the mapping can be edited
            // without rewriting history.
            case VALUE_MAP, XMLPATH -> StepOutcome.of(value);
        };
    }

    /** The previous raw value, from the cache or from the database on a miss. */
    private RawSample previousRaw(long itemId) {
        RawSample cached = rawCache.getIfPresent(itemId);
        if (cached != null) {
            return cached;
        }
        try {
            List<RawSample> rows = jdbc.query(
                    "SELECT raw_value_num, raw_clock FROM item_latest WHERE item_id = ? AND raw_clock IS NOT NULL",
                    (rs, rowNum) -> new RawSample(rs.getDouble(1), rs.getTimestamp(2).toInstant()),
                    itemId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (org.springframework.dao.DataAccessException e) {
            log.debug("Could not read the previous raw value of item {}: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * Records the raw value as the next baseline.
     *
     * <p>Written through to the database rather than held only in memory, so a
     * restart or an item moving between server instances does not lose the
     * baseline and discard the following sample.
     */
    private void storeRaw(long itemId, double value, Instant clock) {
        rawCache.put(itemId, new RawSample(value, clock));
        try {
            jdbc.update("""
                    INSERT INTO item_latest (item_id, clock, raw_value_num, raw_clock)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (item_id) DO UPDATE SET
                        raw_value_num = EXCLUDED.raw_value_num,
                        raw_clock     = EXCLUDED.raw_clock
                    """, itemId, Timestamp.from(clock), value, Timestamp.from(clock));
        } catch (org.springframework.dao.DataAccessException e) {
            // The in-memory baseline still holds, so collection continues; the
            // only cost is a discarded sample if this item later moves hosts.
            log.debug("Could not persist the raw baseline for item {}: {}", itemId, e.getMessage());
        }
    }

    private static String expandGroups(String template, Matcher matcher) {
        String output = template;
        for (int group = matcher.groupCount(); group >= 0; group--) {
            String replacement = matcher.group(group) == null ? "" : matcher.group(group);
            output = output.replace("\\" + group, replacement);
        }
        return output;
    }

    private static String trim(String value, String characters) {
        int start = 0;
        int end = value.length();
        while (start < end && characters.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && characters.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    private static Object extractJson(String document, String path) {
        try {
            JsonNode node = JSON.readTree(document);
            for (String segment : path.replace("[", ".").replace("]", "").split("\\.")) {
                if (segment.isBlank() || "$".equals(segment)) {
                    continue;
                }
                node = segment.matches("\\d+") ? node.path(Integer.parseInt(segment)) : node.path(segment);
            }
            if (node.isMissingNode() || node.isNull()) {
                throw new PreprocessingException("JSON path '" + path + "' matched nothing");
            }
            return node.isValueNode() ? node.asText() : node.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PreprocessingException("value is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static boolean toBoolean(String value) {
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "true", "yes", "on", "up", "running", "ok", "enabled" -> true;
            case "false", "no", "off", "down", "stopped", "unavailable", "disabled" -> false;
            default -> {
                try {
                    yield Double.parseDouble(normalised) != 0;
                } catch (NumberFormatException e) {
                    throw new PreprocessingException("'" + value + "' is not a boolean");
                }
            }
        };
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new PreprocessingException("'" + abbreviate(value) + "' is not numeric");
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new PreprocessingException("parameter '" + value + "' is not numeric");
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }

    /** Drops a cached step list, so an edit takes effect immediately locally. */
    public void invalidate(long itemId) {
        stepCache.invalidate(itemId);
    }

    /** One step's configuration, flattened out of the entity for the hot path. */
    public record StepConfig(int step, PreprocessingType type, List<String> params,
                             PreprocessingErrorHandler errorHandler, String errorHandlerParams) {

        public String param(int index, String defaultValue) {
            return index < params.size() && params.get(index) != null ? params.get(index) : defaultValue;
        }

        public static StepConfig from(ItemPreprocessing entity) {
            return new StepConfig(entity.getStep(), entity.getType(), entity.getParams(),
                    entity.getErrorHandler(), entity.getErrorHandlerParams());
        }
    }

    /** A raw sample retained as the baseline for change and rate steps. */
    private record RawSample(double value, Instant clock) {
    }

    /** Result of one step: either a new value, or an instruction to discard. */
    private record StepOutcome(Object value, boolean discarded) {
        static StepOutcome of(Object value) {
            return new StepOutcome(value, false);
        }

        static StepOutcome discard() {
            return new StepOutcome(null, true);
        }
    }

    /** Raised by a step that cannot be applied to the value it was given. */
    public static class PreprocessingException extends RuntimeException {
        public PreprocessingException(String message) {
            super(message);
        }
    }

    /** Value types a step may legally produce, for validation on save. */
    public static boolean producesNumeric(PreprocessingType type) {
        return switch (type) {
            case MULTIPLIER, CHANGE, CHANGE_PER_SECOND, IN_RANGE, BOOL_TO_DECIMAL,
                 HEX_TO_DECIMAL, OCTAL_TO_DECIMAL, CUSTOM_FORMULA -> true;
            default -> false;
        };
    }

    /** Convenience for callers that only need the resulting value type. */
    public static ItemValueType resultType(ItemValueType declared) {
        return declared == null ? ItemValueType.TEXT : declared;
    }
}
