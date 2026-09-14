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

import java.util.ArrayList;
import java.util.List;

/**
 * One transformation applied to a raw value before it is stored.
 *
 * <p>Steps run in order. Preprocessing is what turns a device's native output
 * into something comparable: an SNMP octet counter into bits per second, a JSON
 * blob into a single number, a vendor string into a status code.
 */
@Entity
@Table(name = "item_preprocessing")
@Getter
@Setter
public class ItemPreprocessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preproc_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "step", nullable = false)
    private int step;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PreprocessingType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private List<String> params = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "error_handler", nullable = false)
    private PreprocessingErrorHandler errorHandler = PreprocessingErrorHandler.ERROR;

    @Column(name = "error_handler_params", nullable = false)
    private String errorHandlerParams = "";
}
