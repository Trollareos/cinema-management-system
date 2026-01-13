package com.example.demo.service;

import com.example.demo.domain.*;
import com.example.demo.error.ApiException;
import com.example.demo.repo.ProgramMemberRepository;
import com.example.demo.repo.ProgramRepository;
import com.example.demo.repo.ScreeningRepository;
import com.example.demo.repo.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScreeningService {

    public enum Visibility { FULL, PUBLIC, NONE }

    private final ScreeningRepository screeningRepo;
    private final ProgramRepository programRepo;
    private final UserRepository userRepo;
    private final ProgramMemberRepository memberRepo;
    private final AuditLogService audit;

    public ScreeningService(ScreeningRepository screeningRepo,
                            ProgramRepository programRepo,
                            UserRepository userRepo,
                            ProgramMemberRepository memberRepo,
                            AuditLogService audit) {
        this.screeningRepo = screeningRepo;
        this.programRepo = programRepo;
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.audit = audit;
    }

    // =========================
    // Helpers
    // =========================

    private Program requireProgram(Long programId) {
        return programRepo.findById(programId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND"));
    }

    private AppUser requireUser(String username) {
        return userRepo.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private Screening requireScreening(Long id) {
        return screeningRepo.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND"));
    }

    private ProgramRole roleOf(Long programId, String username) {
        if (username == null || username.isBlank()) return null;
        return memberRepo.findByProgramIdAndUserUsernameIgnoreCase(programId, username)
                .map(ProgramMember::getRole)
                .orElse(null);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void ensureProgramState(Program program, ProgramState required) {
        if (program.getState() != required) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_STATE_INVALID",
                    "Program state must be " + required + " (now: " + program.getState() + ")");
        }
    }

    private void validateDurationFits(Screening s) {
        if (s.getStartTime() == null || s.getEndTime() == null) return;
        if (s.getFilmDurationMinutes() == null || s.getFilmDurationMinutes() <= 0) return;

        long mins = Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
        if (mins < s.getFilmDurationMinutes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCREENING_DURATION_INVALID",
                    "endTime must be >= startTime + filmDurationMinutes");
        }
    }

    /**
     * Complete for SUBMISSION:
     * απαιτούνται: film info + auditorium + duration.
     * start/end μπορούν να μπουν αργότερα (SCHEDULING/DECISION),
     * αλλά αν υπάρχει startTime και δεν υπάρχει endTime -> παράγεται endTime.
     */
    private void ensureCompleteForSubmission(Screening s) {
        if (isBlank(s.getFilmTitle())
                || isBlank(s.getFilmCast())
                || isBlank(s.getFilmGenres())
                || s.getFilmDurationMinutes() == null || s.getFilmDurationMinutes() <= 0
                || isBlank(s.getAuditoriumName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCREENING_INCOMPLETE_FOR_SUBMISSION");
        }

        if (s.getStartTime() != null && s.getEndTime() == null) {
            s.setEndTime(s.getStartTime().plusMinutes(s.getFilmDurationMinutes()));
        }

        if (s.getEndTime() != null && s.getStartTime() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "START_TIME_REQUIRED_IF_END_TIME_SET");
        }

        validateDurationFits(s);
    }

    /**
     * Visibility rules:
     * - FULL: PROGRAMMER of program OR assigned handler OR submitter(owner)
     * - PUBLIC: if program ANNOUNCED AND screening SCHEDULED
     * - NONE: otherwise
     */
    @Transactional(readOnly = true)
    public Visibility visibilityFor(Screening s, String usernameOrNull) {
        Program p = s.getProgram();

        boolean publicVisible =
                p != null
                        && p.getState() == ProgramState.ANNOUNCED
                        && s.getState() == ScreeningState.SCHEDULED;

        if (usernameOrNull == null || usernameOrNull.isBlank()) {
            return publicVisible ? Visibility.PUBLIC : Visibility.NONE;
        }

        String me = usernameOrNull;

        ProgramRole myRole = roleOf(p.getId(), me);
        if (myRole == ProgramRole.PROGRAMMER) return Visibility.FULL;

        if (s.getHandler() != null && s.getHandler().getUsername() != null
                && s.getHandler().getUsername().equalsIgnoreCase(me)) {
            return Visibility.FULL;
        }

        if (s.getSubmitter() != null && s.getSubmitter().getUsername() != null
                && s.getSubmitter().getUsername().equalsIgnoreCase(me)) {
            return Visibility.FULL;
        }

        // authenticated but not member => visitor rights
        return publicVisible ? Visibility.PUBLIC : Visibility.NONE;
    }

    @Transactional(readOnly = true)
    public Screening getForView(Long screeningId, String usernameOrNull) {
        Screening s = requireScreening(screeningId);
        Visibility v = visibilityFor(s, usernameOrNull);
        if (v == Visibility.NONE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND");
        }
        return s;
    }

    // =========================
    // Commands
    // =========================

    @Transactional
    public Screening create(Long programId, String me) {
        if (me == null || me.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        Program program = requireProgram(programId);

        ProgramRole existingRole = roleOf(programId, me);
        if (existingRole == ProgramRole.PROGRAMMER || existingRole == ProgramRole.STAFF) {
            throw new AccessDeniedException("Programmer/Staff cannot submit screenings in the same program");
        }

        AppUser submitter = requireUser(me);

        // if no role yet -> add SUBMITTER role
        if (existingRole == null) {
            ProgramMember pm = new ProgramMember();
            pm.setProgram(program);
            pm.setUser(submitter);
            pm.setRole(ProgramRole.SUBMITTER);
            memberRepo.save(pm);
        }

        Screening s = new Screening();
        s.setProgram(program);
        s.setSubmitter(submitter);
        s.setState(ScreeningState.CREATED);

        Screening saved = screeningRepo.save(s);

        audit.log(me, "SCREENING_CREATE", programId, saved.getId(), "created screening");
        return saved;
    }

    @Transactional
    public Screening update(Long screeningId, String me,
                            String filmTitle, String filmCast, String filmGenres,
                            Integer filmDurationMinutes, String auditoriumName,
                            LocalDateTime startTime, LocalDateTime endTime) {
        if (me == null || me.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        Screening s = requireScreening(screeningId);

        if (s.getSubmitter() == null || s.getSubmitter().getUsername() == null
                || !s.getSubmitter().getUsername().equalsIgnoreCase(me)) {
            throw new AccessDeniedException("Only submitter(owner) can update this screening");
        }

        ProgramRole role = roleOf(s.getProgram().getId(), me);
        if (role != ProgramRole.SUBMITTER) {
            throw new AccessDeniedException("Only SUBMITTER can update screenings");
        }

        if (s.getState() != ScreeningState.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_UPDATE_ONLY_CREATED");
        }

        if (filmTitle != null) s.setFilmTitle(filmTitle);
        if (filmCast != null) s.setFilmCast(filmCast);
        if (filmGenres != null) s.setFilmGenres(filmGenres);
        if (filmDurationMinutes != null) s.setFilmDurationMinutes(filmDurationMinutes);
        if (auditoriumName != null) s.setAuditoriumName(auditoriumName);
        if (startTime != null) s.setStartTime(startTime);
        if (endTime != null) s.setEndTime(endTime);

        // derive endTime if possible (startTime + duration)
        if (s.getStartTime() != null && s.getEndTime() == null
                && s.getFilmDurationMinutes() != null && s.getFilmDurationMinutes() > 0) {
            s.setEndTime(s.getStartTime().plusMinutes(s.getFilmDurationMinutes()));
        }

        if (s.getEndTime() != null && s.getStartTime() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "START_TIME_REQUIRED_IF_END_TIME_SET");
        }

        validateDurationFits(s);

        Screening saved = screeningRepo.save(s);

        audit.log(me, "SCREENING_UPDATE", s.getProgram().getId(), s.getId(), "updated fields in CREATED");
        return saved;
    }

    @Transactional
    public Screening submit(Long screeningId, String me) {
        if (me == null || me.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        Screening s = requireScreening(screeningId);

        if (s.getSubmitter() == null || s.getSubmitter().getUsername() == null
                || !s.getSubmitter().getUsername().equalsIgnoreCase(me)) {
            throw new AccessDeniedException("Only submitter(owner) can submit this screening");
        }

        ProgramRole role = roleOf(s.getProgram().getId(), me);
        if (role != ProgramRole.SUBMITTER) {
            throw new AccessDeniedException("Only SUBMITTER can submit screenings");
        }

        if (s.getState() != ScreeningState.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_SUBMIT_ONLY_CREATED");
        }

        Program program = s.getProgram();
        ensureProgramState(program, ProgramState.SUBMISSION);

        ensureCompleteForSubmission(s);

        s.setState(ScreeningState.SUBMITTED);
        Screening saved = screeningRepo.save(s);

        audit.log(me, "SCREENING_SUBMIT", program.getId(), s.getId(), "CREATED->SUBMITTED");
        return saved;
    }

    @Transactional
    public void withdraw(Long screeningId, String me) {
        if (me == null || me.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        Screening s = requireScreening(screeningId);

        if (s.getSubmitter() == null || s.getSubmitter().getUsername() == null
                || !s.getSubmitter().getUsername().equalsIgnoreCase(me)) {
            throw new AccessDeniedException("Only submitter(owner) can withdraw this screening");
        }

        ProgramRole role = roleOf(s.getProgram().getId(), me);
        if (role != ProgramRole.SUBMITTER) {
            throw new AccessDeniedException("Only SUBMITTER can withdraw screenings");
        }

        if (s.getState() != ScreeningState.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_WITHDRAW_ONLY_CREATED");
        }

        Long pid = s.getProgram().getId();
        Long sid = s.getId();

        screeningRepo.delete(s);

        audit.log(me, "SCREENING_WITHDRAW_DELETE", pid, sid, "deleted CREATED screening");
    }

    @Transactional
    public Screening assignHandler(Long screeningId, String programmerUsername, String staffUsername) {
        if (programmerUsername == null || programmerUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        if (isBlank(staffUsername)) throw new ApiException(HttpStatus.BAD_REQUEST, "STAFF_USERNAME_REQUIRED");

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        ensureProgramState(program, ProgramState.ASSIGNMENT);

        if (roleOf(program.getId(), programmerUsername) != ProgramRole.PROGRAMMER) {
            throw new AccessDeniedException("Only PROGRAMMER can assign handlers");
        }

        if (s.getState() != ScreeningState.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_ASSIGN_ONLY_SUBMITTED");
        }

        if (s.getHandler() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDLER_ALREADY_ASSIGNED");
        }

        ProgramMember staffMember = memberRepo.findByProgramIdAndUserUsernameIgnoreCase(program.getId(), staffUsername)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));

        if (staffMember.getRole() != ProgramRole.STAFF) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_NOT_STAFF_IN_PROGRAM");
        }

        s.setHandler(staffMember.getUser());
        Screening saved = screeningRepo.save(s);

        audit.log(programmerUsername, "SCREENING_ASSIGN_HANDLER", program.getId(), s.getId(),
                "handler=" + staffMember.getUser().getUsername());

        return saved;
    }

    @Transactional
    public Screening review(Long screeningId, String staffUsername, Integer score, String comments) {
        if (staffUsername == null || staffUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        ensureProgramState(program, ProgramState.REVIEW);

        if (s.getHandler() == null || s.getHandler().getUsername() == null
                || !s.getHandler().getUsername().equalsIgnoreCase(staffUsername)) {
            throw new AccessDeniedException("Only assigned handler can review this screening");
        }

        if (s.getState() != ScreeningState.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_REVIEW_ONLY_SUBMITTED");
        }

        if (score == null || score < 0 || score > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_SCORE_INVALID", "Score must be 0..10");
        }

        s.setReviewScore(score);
        s.setReviewComments(comments != null ? comments : "");
        s.setState(ScreeningState.REVIEWED);

        Screening saved = screeningRepo.save(s);

        audit.log(staffUsername, "SCREENING_REVIEW", program.getId(), s.getId(),
                "score=" + score);

        return saved;
    }

    @Transactional
    public Screening approve(Long screeningId, String submitterUsername, String conditionalNotes) {
        if (submitterUsername == null || submitterUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        ensureProgramState(program, ProgramState.SCHEDULING);

        if (s.getSubmitter() == null || s.getSubmitter().getUsername() == null
                || !s.getSubmitter().getUsername().equalsIgnoreCase(submitterUsername)) {
            throw new AccessDeniedException("Only submitter(owner) can approve this screening");
        }

        ProgramRole role = roleOf(program.getId(), submitterUsername);
        if (role != ProgramRole.SUBMITTER) {
            throw new AccessDeniedException("Only SUBMITTER can approve screenings");
        }

        if (s.getState() != ScreeningState.REVIEWED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_APPROVE_ONLY_REVIEWED");
        }

        s.setConditionalNotes(conditionalNotes);
        s.setState(ScreeningState.APPROVED);

        Screening saved = screeningRepo.save(s);

        audit.log(submitterUsername, "SCREENING_APPROVE", program.getId(), s.getId(), "REVIEWED->APPROVED");
        return saved;
    }

    @Transactional
    public Screening reject(Long screeningId, String programmerUsername, String reason) {
        if (programmerUsername == null || programmerUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        if (isBlank(reason)) throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTION_REASON_REQUIRED");

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        if (program.getState() != ProgramState.SCHEDULING && program.getState() != ProgramState.DECISION) {
            throw new ApiException(HttpStatus.CONFLICT, "REJECTION_ALLOWED_ONLY_SCHEDULING_OR_DECISION");
        }

        if (roleOf(program.getId(), programmerUsername) != ProgramRole.PROGRAMMER) {
            throw new AccessDeniedException("Only PROGRAMMER can reject screenings");
        }

        if (s.getState() == ScreeningState.SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REJECT_SCHEDULED");
        }
        if (s.getState() == ScreeningState.REJECTED) {
            return s; // idempotent-ish
        }

        s.setRejectionReason(reason.trim());
        s.setState(ScreeningState.REJECTED);

        Screening saved = screeningRepo.save(s);

        audit.log(programmerUsername, "SCREENING_REJECT", program.getId(), s.getId(),
                "reason=" + reason.trim());

        return saved;
    }

    @Transactional
    public Screening finalSubmit(Long screeningId, String submitterUsername, String finalBundleNotes) {
        if (submitterUsername == null || submitterUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        ensureProgramState(program, ProgramState.FINAL_SUBMISSION);

        if (s.getSubmitter() == null || s.getSubmitter().getUsername() == null
                || !s.getSubmitter().getUsername().equalsIgnoreCase(submitterUsername)) {
            throw new AccessDeniedException("Only submitter(owner) can final-submit this screening");
        }

        ProgramRole role = roleOf(program.getId(), submitterUsername);
        if (role != ProgramRole.SUBMITTER) {
            throw new AccessDeniedException("Only SUBMITTER can final-submit screenings");
        }

        if (s.getState() != ScreeningState.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_SUBMIT_ONLY_APPROVED");
        }

        s.setFinalBundleNotes(finalBundleNotes);
        s.setFinalSubmittedAt(Instant.now());

        Screening saved = screeningRepo.save(s);

        audit.log(submitterUsername, "SCREENING_FINAL_SUBMIT", program.getId(), s.getId(), "finalSubmittedAt set");
        return saved;
    }

    @Transactional
    public Screening acceptToSchedule(Long screeningId, String programmerUsername) {
        if (programmerUsername == null || programmerUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }

        Screening s = requireScreening(screeningId);
        Program program = s.getProgram();

        ensureProgramState(program, ProgramState.DECISION);

        if (roleOf(program.getId(), programmerUsername) != ProgramRole.PROGRAMMER) {
            throw new AccessDeniedException("Only PROGRAMMER can accept screenings into schedule");
        }

        if (s.getState() != ScreeningState.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCEPT_ONLY_APPROVED");
        }
        if (s.getFinalSubmittedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCEPT_REQUIRES_FINAL_SUBMISSION");
        }

        // Scheduling requires startTime + duration + auditorium + film fields.
        if (isBlank(s.getFilmTitle())
                || isBlank(s.getFilmGenres())
                || s.getFilmDurationMinutes() == null || s.getFilmDurationMinutes() <= 0
                || isBlank(s.getAuditoriumName())
                || s.getStartTime() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_SCHEDULE_INCOMPLETE",
                    "Scheduling requires film/auditorium/startTime/duration");
        }

        if (s.getEndTime() == null) {
            s.setEndTime(s.getStartTime().plusMinutes(s.getFilmDurationMinutes()));
        }
        validateDurationFits(s);

        s.setState(ScreeningState.SCHEDULED);
        Screening saved = screeningRepo.save(s);

        audit.log(programmerUsername, "SCREENING_ACCEPT_SCHEDULE", program.getId(), s.getId(), "APPROVED->SCHEDULED");
        return saved;
    }

    // =========================
    // Search
    // =========================
    @Transactional(readOnly = true)
    public List<Screening> search(Long programId, String usernameOrNull,
                                 String title, String cast, String genre,
                                 LocalDate startFrom, LocalDate startTo,
                                 boolean timetable) {

        requireProgram(programId);

        Specification<Screening> spec = (root, q, cb) -> cb.conjunction();
        spec = spec.and((root, q, cb) -> cb.equal(root.get("program").get("id"), programId));

        if (!isBlank(title)) {
            for (String w : title.trim().toLowerCase().split("\\s+")) {
                String like = "%" + w + "%";
                spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("filmTitle")), like));
            }
        }

        if (!isBlank(cast)) {
            for (String w : cast.trim().toLowerCase().split("\\s+")) {
                String like = "%" + w + "%";
                spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("filmCast")), like));
            }
        }

        if (!isBlank(genre)) {
            for (String w : genre.trim().toLowerCase().split("\\s+")) {
                String like = "%" + w + "%";
                spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("filmGenres")), like));
            }
        }

        if (startFrom != null) {
            LocalDateTime from = startFrom.atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("startTime"), from));
        }

        if (startTo != null) {
            LocalDateTime toExclusive = startTo.plusDays(1).atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("startTime"), toExclusive));
        }

        Sort sort = timetable
                ? Sort.by(Sort.Direction.ASC, "startTime")
                : Sort.by(Sort.Direction.ASC, "filmGenres", "filmTitle");

        List<Screening> raw = screeningRepo.findAll(spec, sort);

        return raw.stream()
                .filter(s -> visibilityFor(s, usernameOrNull) != Visibility.NONE)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Screening> publicScheduledForProgram(Long programId) {
        Program program = requireProgram(programId);
        if (program.getState() != ProgramState.ANNOUNCED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND");
        }

        return screeningRepo.findByProgramIdAndState(programId, ScreeningState.SCHEDULED)
                .stream()
                .sorted((a, b) -> {
                    if (a.getStartTime() == null && b.getStartTime() == null) return 0;
                    if (a.getStartTime() == null) return 1;
                    if (b.getStartTime() == null) return -1;
                    return a.getStartTime().compareTo(b.getStartTime());
                })
                .toList();
    }
}
