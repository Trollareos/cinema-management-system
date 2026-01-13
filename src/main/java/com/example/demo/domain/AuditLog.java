package com.example.demo.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_actor", columnList = "actorUsername"),
                @Index(name = "idx_audit_program", columnList = "programId"),
                @Index(name = "idx_audit_screening", columnList = "screeningId"),
                @Index(name = "idx_audit_at", columnList = "at")
        }
)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(length = 120)
    private String actorUsername;

    @Column(nullable = false, length = 120)
    private String action;

    private Long programId;
    private Long screeningId;

    @Column(length = 4000)
    private String details;

    @PrePersist
    void prePersist() {
        if (at == null) at = Instant.now();
    }

    public Long getId() { return id; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Long getProgramId() { return programId; }
    public void setProgramId(Long programId) { this.programId = programId; }

    public Long getScreeningId() { return screeningId; }
    public void setScreeningId(Long screeningId) { this.screeningId = screeningId; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
