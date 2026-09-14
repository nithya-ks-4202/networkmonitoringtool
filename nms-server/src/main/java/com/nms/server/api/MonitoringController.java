package com.nms.server.api;

import com.nms.common.ItemState;
import com.nms.common.ItemValueType;
import com.nms.server.domain.HostClass;
import com.nms.server.history.HistoryQueryService;
import com.nms.server.repository.ItemLatestRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.projection.HostItemValue;
import com.nms.server.repository.projection.LatestValue;
import com.nms.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Serves collected data: latest values, graph series and the camera wall.
 */
@RestController
@RequestMapping("/api/monitoring")
@Tag(name = "Monitoring", description = "Collected values and graphs")
public class MonitoringController {

    /**
     * Points returned for a graph.
     *
     * <p>Capped well below what a range could contain: a 1,000-pixel chart
     * cannot show more, and sending more would spend bandwidth and browser
     * time producing pixels nobody sees.
     */
    private static final int DEFAULT_GRAPH_POINTS = 600;
    private static final int MAX_GRAPH_POINTS = 2_000;

    private final ItemLatestRepository latestValues;
    private final ItemRepository items;
    private final HistoryQueryService history;

    public MonitoringController(ItemLatestRepository latestValues,
                                ItemRepository items,
                                HistoryQueryService history) {
        this.latestValues = latestValues;
        this.items = items;
        this.history = history;
    }

    @GetMapping("/hosts/{hostId}/latest")
    @PreAuthorize("hasAuthority('item.read')")
    @Operation(summary = "Latest value of every item on a host")
    @Transactional(readOnly = true)
    public List<LatestValueView> latestForHost(@PathVariable Long hostId) {
        return latestValues.findLatestForHost(hostId).stream()
                .map(LatestValueView::from)
                .toList();
    }

    @GetMapping("/items/{itemId}/history")
    @PreAuthorize("hasAuthority('item.read')")
    @Operation(summary = "Graph series for an item, from history or trends by range")
    @Transactional(readOnly = true)
    public ResponseEntity<GraphSeries> history(
            @PathVariable Long itemId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false, defaultValue = "600") int points) {

        var item = items.findById(itemId)
                .filter(candidate -> candidate.getTenantId().equals(AuthenticatedUser.currentTenantId()))
                .orElse(null);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        if (!item.getValueType().isNumeric()) {
            return ResponseEntity.badRequest().build();
        }

        Instant until = to == null ? Instant.now() : to;
        Instant since = from == null ? until.minus(Duration.ofHours(6)) : from;
        if (!since.isBefore(until)) {
            return ResponseEntity.badRequest().build();
        }

        int requestedPoints = points <= 0 ? DEFAULT_GRAPH_POINTS : Math.min(points, MAX_GRAPH_POINTS);
        List<HistoryQueryService.GraphPoint> series =
                history.graphSeries(itemId, item.getValueType(), since, until, requestedPoints);

        return ResponseEntity.ok(new GraphSeries(
                itemId, item.getName(), item.getUnits(), item.getValueType(),
                since, until, series));
    }

    /**
     * State of every camera, for a wall display.
     *
     * <p>One query for the whole grid rather than one per camera: this view is
     * typically left open on a screen refreshing every thirty seconds, and a
     * per-camera round trip would make a few hundred devices unusable.
     */
    @GetMapping("/cameras")
    @PreAuthorize("hasAuthority('host.read')")
    @Operation(summary = "Online/offline state of every camera")
    @Transactional(readOnly = true)
    public List<CameraStatus> cameras(
            @RequestParam(required = false, defaultValue = "icmpping") String itemKey,
            @RequestParam(required = false) String group) {

        Long tenantId = AuthenticatedUser.currentTenantId();

        List<HostItemValue> values = group == null || group.isBlank()
                // Falls back to the host class so a camera nobody filed into
                // the Cameras group still appears on the wall.
                ? latestValues.findLatestByHostClassAndKey(tenantId, HostClass.CAMERA, itemKey)
                : latestValues.findLatestByGroupAndKey(tenantId, group, itemKey);

        return values.stream().map(CameraStatus::from).toList();
    }

    /** An item's current value, rendered for display. */
    public record LatestValueView(
            Long itemId,
            String name,
            String key,
            String units,
            ItemValueType valueType,
            ItemState state,
            String error,
            Instant clock,
            String value,
            Double numericValue,
            String age) {

        static LatestValueView from(LatestValue source) {
            return new LatestValueView(
                    source.itemId(), source.name(), source.key(), source.units(),
                    source.valueType(), source.state(), source.error(), source.clock(),
                    renderValue(source), source.valueNum(),
                    source.clock() == null ? "never"
                            : com.nms.server.util.Durations.human(
                                    Duration.between(source.clock(), Instant.now())));
        }

        private static String renderValue(LatestValue source) {
            if (source.valueStr() != null && !source.valueStr().isBlank()) {
                return source.valueStr();
            }
            if (source.valueNum() == null) {
                return "";
            }
            double value = source.valueNum();
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }

    /** One camera's state on the wall. */
    public record CameraStatus(
            Long hostId,
            String hostName,
            String technicalName,
            Status status,
            Instant lastSeen,
            String age,
            String error) {

        /** What the wall shows for one device. */
        public enum Status {
            ONLINE,
            OFFLINE,
            /**
             * Collection itself has failed, so the camera's state is unknown.
             * Kept distinct from OFFLINE: "we cannot tell" and "it is down"
             * call for different responses, and showing the first as the
             * second sends someone to check a camera that may be fine.
             */
            UNKNOWN
        }

        static CameraStatus from(HostItemValue source) {
            Status status;
            if (source.state() == ItemState.NOT_SUPPORTED || !source.hasValue()) {
                status = Status.UNKNOWN;
            } else if (isStale(source.clock())) {
                // A value old enough to predate several polling intervals says
                // nothing about the present.
                status = Status.UNKNOWN;
            } else {
                status = source.valueNum() != null && source.valueNum() > 0
                        ? Status.ONLINE : Status.OFFLINE;
            }

            return new CameraStatus(
                    source.hostId(), source.hostName(), source.technicalName(), status,
                    source.clock(),
                    source.clock() == null ? "never"
                            : com.nms.server.util.Durations.human(
                                    Duration.between(source.clock(), Instant.now())),
                    source.error());
        }

        private static boolean isStale(Instant clock) {
            return clock == null || clock.isBefore(Instant.now().minus(Duration.ofMinutes(10)));
        }
    }

    /** A graph series with the metadata needed to label its axes. */
    public record GraphSeries(
            Long itemId,
            String name,
            String units,
            ItemValueType valueType,
            Instant from,
            Instant to,
            List<HistoryQueryService.GraphPoint> points) {
    }
}
