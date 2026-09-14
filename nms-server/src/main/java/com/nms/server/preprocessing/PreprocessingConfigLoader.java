package com.nms.server.preprocessing;

import com.nms.server.domain.PreprocessingErrorHandler;
import com.nms.server.domain.PreprocessingType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads an item's preprocessing steps.
 *
 * <p>Queried through JDBC rather than JPA because this is called from poller
 * worker threads that hold no persistence session, and because the caller wants
 * a flat, immutable snapshot it can cache -- not a set of managed entities.
 */
@Component
public class PreprocessingConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingConfigLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public PreprocessingConfigLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads the ordered steps for an item.
     *
     * @return an empty list when the item has none, which is the common case
     */
    public List<PreprocessingPipeline.StepConfig> loadSteps(Long itemId) {
        try {
            return jdbc.query("""
                    SELECT step, type, params, error_handler, error_handler_params
                    FROM item_preprocessing
                    WHERE item_id = ?
                    ORDER BY step
                    """,
                    (rs, rowNum) -> new PreprocessingPipeline.StepConfig(
                            rs.getInt("step"),
                            PreprocessingType.valueOf(rs.getString("type")),
                            parseParams(rs.getString("params")),
                            PreprocessingErrorHandler.valueOf(rs.getString("error_handler")),
                            rs.getString("error_handler_params")),
                    itemId);
        } catch (org.springframework.dao.DataAccessException | IllegalArgumentException e) {
            // A malformed step must not stop the value being stored: losing
            // preprocessing is a degradation, losing the sample is data loss.
            log.warn("Could not load preprocessing for item {}; storing values unprocessed: {}",
                    itemId, e.getMessage());
            return List.of();
        }
    }

    private static List<String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> params = new ArrayList<>();
            JSON.readTree(json).forEach(node -> params.add(node.asText()));
            return List.copyOf(params);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }
}
