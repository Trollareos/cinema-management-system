package com.example.demo.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "screenings")
public class Screening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Σε ποιο πρόγραμμα ανήκει
    @ManyToOne(optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    // Ποιος submitter το δημιούργησε
    @ManyToOne(optional = false)
    @JoinColumn(name = "submitter_id", nullable = false)
    private AppUser submitter;

    // Ποιος staff έχει ανατεθεί να το κάνει review (nullable μέχρι να γίνει assign)
    @ManyToOne
    @JoinColumn(name = "handler_id")
    private AppUser handler;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningState state = ScreeningState.CREATED;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // ====== Screening details (μπορούν να συμπληρωθούν όσο είναι CREATED) ======
    private String auditoriumName;

    private String filmTitle;

    @Column(length = 2000)
    private String filmCast;

    @Column(length = 1000)
    private String filmGenres;

    private Integer filmDurationMinutes;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // ====== Review (μόνο staff/handler στο REVIEW) ======
    private Integer reviewScore;

    @Column(length = 4000)
    private String reviewComments;

    // ====== Scheduling / decision ======
    @Column(length = 2000)
    private String conditionalNotes;

    @Column(length = 2000)
    private String rejectionReason;

    // ====== Final submission ======
    private Instant finalSubmittedAt;

    @Column(length = 4000)
    private String finalBundleNotes;

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        if (this.state == null) this.state = ScreeningState.CREATED;
    }

    // ====== getters / setters ======
    public Long getId() { return id; }

    public Program getProgram() { return program; }
    public void setProgram(Program program) { this.program = program; }

    public AppUser getSubmitter() { return submitter; }
    public void setSubmitter(AppUser submitter) { this.submitter = submitter; }

    public AppUser getHandler() { return handler; }
    public void setHandler(AppUser handler) { this.handler = handler; }

    public ScreeningState getState() { return state; }
    public void setState(ScreeningState state) { this.state = state; }

    public Instant getCreatedAt() { return createdAt; }

    public String getAuditoriumName() { return auditoriumName; }
    public void setAuditoriumName(String auditoriumName) { this.auditoriumName = auditoriumName; }

    public String getFilmTitle() { return filmTitle; }
    public void setFilmTitle(String filmTitle) { this.filmTitle = filmTitle; }

    public String getFilmCast() { return filmCast; }
    public void setFilmCast(String filmCast) { this.filmCast = filmCast; }

    public String getFilmGenres() { return filmGenres; }
    public void setFilmGenres(String filmGenres) { this.filmGenres = filmGenres; }

    public Integer getFilmDurationMinutes() { return filmDurationMinutes; }
    public void setFilmDurationMinutes(Integer filmDurationMinutes) { this.filmDurationMinutes = filmDurationMinutes; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Integer getReviewScore() { return reviewScore; }
    public void setReviewScore(Integer reviewScore) { this.reviewScore = reviewScore; }

    public String getReviewComments() { return reviewComments; }
    public void setReviewComments(String reviewComments) { this.reviewComments = reviewComments; }

    public String getConditionalNotes() { return conditionalNotes; }
    public void setConditionalNotes(String conditionalNotes) { this.conditionalNotes = conditionalNotes; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getFinalSubmittedAt() { return finalSubmittedAt; }
    public void setFinalSubmittedAt(Instant finalSubmittedAt) { this.finalSubmittedAt = finalSubmittedAt; }

    public String getFinalBundleNotes() { return finalBundleNotes; }
    public void setFinalBundleNotes(String finalBundleNotes) { this.finalBundleNotes = finalBundleNotes; }
}
