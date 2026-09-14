package com.nms.server.domain;

import com.nms.common.Severity;
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

import java.time.Instant;

/**
 * An operator action taken on a problem.
 *
 * <p>Each action is a separate row, forming a timeline rather than a set of
 * mutable flags. "Who acknowledged this, when, and what did they say" is the
 * question asked in every post-incident review, and a flag on the problem
 * cannot answer it.
 */
@Entity
@Table(name = "problem_ack")
@Getter
@Setter
public class ProblemAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ack_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "clock", nullable = false)
    private Instant clock = Instant.now();

    @Column(name = "message", nullable = false)
    private String message = "";

    @Column(name = "action_acknowledge", nullable = false)
    private boolean acknowledge = false;

    @Column(name = "action_unacknowledge", nullable = false)
    private boolean unacknowledge = false;

    @Column(name = "action_close", nullable = false)
    private boolean close = false;

    @Column(name = "action_message", nullable = false)
    private boolean comment = false;

    @Column(name = "action_severity", nullable = false)
    private boolean changeSeverity = false;

    @Column(name = "action_suppress", nullable = false)
    private boolean suppress = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_severity")
    private Severity newSeverity;

    @Column(name = "suppress_until")
    private Instant suppressUntil;
}
