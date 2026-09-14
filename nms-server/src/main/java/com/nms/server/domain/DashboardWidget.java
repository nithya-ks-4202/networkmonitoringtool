package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/** One panel on a dashboard, positioned on a 24-column grid. */
@Entity
@Table(name = "dashboard_widget")
@Getter
@Setter
public class DashboardWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "widget_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dashboard_id", nullable = false)
    private Dashboard dashboard;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private WidgetType type;

    @Column(name = "name", nullable = false)
    private String name = "";

    @Column(name = "pos_x", nullable = false)
    private int posX = 0;

    @Column(name = "pos_y", nullable = false)
    private int posY = 0;

    @Column(name = "width", nullable = false)
    private int width = 12;

    @Column(name = "height", nullable = false)
    private int height = 5;

    /** Widget-specific settings: which hosts, which items, filters, limits. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config = new HashMap<>();
}
