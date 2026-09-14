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
 * A tag copied onto a problem when it was raised.
 *
 * <p>Copied rather than referenced, so that re-tagging a host later does not
 * silently rewrite the routing of problems that already happened.
 */
@Entity
@Table(name = "problem_tag")
@Getter
@Setter
public class ProblemTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(name = "tag", nullable = false)
    private String tag;

    @Column(name = "value", nullable = false)
    private String value = "";
}
