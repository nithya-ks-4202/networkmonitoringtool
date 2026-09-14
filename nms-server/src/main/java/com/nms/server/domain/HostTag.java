package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A key/value label on a host.
 *
 * <p>Tags flow onto the problems raised for that host, which is what lets an
 * action route by {@code site=london} or {@code env=production} without
 * enumerating hosts.
 */
@Entity
@Table(name = "host_tag")
@Getter
@Setter
public class HostTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @Column(name = "tag", nullable = false)
    private String tag;

    @Column(name = "value", nullable = false)
    private String value = "";
}
