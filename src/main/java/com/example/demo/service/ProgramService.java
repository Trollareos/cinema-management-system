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

import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.*;

@Service
public class ProgramService {

    private final ProgramRepository programRepo;
    private final ScreeningRepository screeningRepo;
    private final ProgramMemberRepository memberRepo;
    private final UserRepository userRepo;
    private final AuditLogService audit;

    public ProgramService(ProgramRepository programRepo,
                          ScreeningRepository screeningRepo,
                          ProgramMemberRepository memberRepo,
                          UserRepository userRepo,
                          AuditLogService audit) {
        this.programRepo = programRepo;
        this.screeningRepo = screeningRepo;
        this.memberRepo = memberRepo;
        this.userRepo = userRepo;
        this.audit = audit;
    }

    // -------------------------
    // helpers
    // -------------------------

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private Program requireProgram(Long id) {
        return programRepo.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND"));
    }

    private ProgramRole roleOf(Long programId, String usernameOrNull) {
        if (usernameOrNull == null || usernameOrNull.isBlank()) return null;
        return memberRepo.findByProgramIdAndUserUsernameIgnoreCase(programId, usernameOrNull)
                .map(ProgramMember::getRole)
                .orElse(null);
    }

    private void requireProgrammer(Long programId, String username) {
        ProgramRole role = roleOf(programId, username);
        if (role != ProgramRole.PROGRAMMER) {
            throw new AccessDeniedException("Only PROGRAMMER can manage this program");
        }
    }

    private void ensureNotAnnounced(Program p) {
        if (p.getState() == ProgramState.ANNOUNCED) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_LOCKED");
        }
    }

    private void validateCreateOrUpdate(String name, String description, LocalDate startDate, LocalDate endDate) {
        if (isBlank(name)) throw new ApiException(400, "PROGRAM_NAME_REQUIRED");
        if (isBlank(description)) throw new ApiException(400, "PROGRAM_DESCRIPTION_REQUIRED");
        if (startDate == null) throw new ApiException(400, "PROGRAM_START_DATE_REQUIRED");
        if (endDate == null) throw new ApiException(400, "PROGRAM_END_DATE_REQUIRED");
        if (endDate.isBefore(startDate)) throw new ApiException(400, "PROGRAM_DATES_INVALID");
    }

    private boolean allowedTransition(ProgramState from, ProgramState to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case CREATED -> to == ProgramState.SUBMISSION;
            case SUBMISSION -> to == ProgramState.ASSIGNMENT;
            case ASSIGNMENT -> to == ProgramState.REVIEW;
            case REVIEW -> to == ProgramState.SCHEDULING;
            case SCHEDULING -> to == ProgramState.FINAL_SUBMISSION;
            case FINAL_SUBMISSION -> to == ProgramState.DECISION;
            case DECISION -> to == ProgramState.ANNOUNCED;
            case ANNOUNCED -> false;
        };
    }

    private Specification<Program> buildSearchSpec(
            String name,
            String description,
            LocalDate dateFrom,
            LocalDate dateTo,
            String filmTitle,
            String auditoriumName
    ) {
        Specification<Program> spec = (root, q, cb) -> cb.conjunction();

        if (!isBlank(name)) {
            String like = "%" + name.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("name")), like));
        }

        if (!isBlank(description)) {
            String like = "%" + description.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("description")), like));
        }

        // date overlap: program [startDate,endDate] overlaps [dateFrom,dateTo]
        if (dateFrom != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("endDate"), dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("startDate"), dateTo));
        }

        if (!isBlank(filmTitle)) {
            String like = "%" + filmTitle.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> {
                Subquery<Long> sq = q.subquery(Long.class);
                var s = sq.from(Screening.class);
                sq.select(cb.literal(1L));
                sq.where(
                        cb.and(
                                cb.equal(s.get("program").get("id"), root.get("id")),
                                cb.like(cb.lower(s.get("filmTitle")), like)
                        )
                );
                return cb.exists(sq);
            });
        }

        if (!isBlank(auditoriumName)) {
            String like = "%" + auditoriumName.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> {
                Subquery<Long> sq = q.subquery(Long.class);
                var s = sq.from(Screening.class);
                sq.select(cb.literal(1L));
                sq.where(
                        cb.and(
                                cb.equal(s.get("program").get("id"), root.get("id")),
                                cb.like(cb.lower(s.get("auditoriumName")), like)
                        )
                );
                return cb.exists(sq);
            });
        }

        return spec;
    }

    @Transactional(readOnly = true)
    public List<String> programmerUsernames(Long programId) {
        return memberRepo.findByProgramIdAndRole(programId, ProgramRole.PROGRAMMER)
                .stream()
                .map(m -> m.getUser().getUsername())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> staffUsernames(Long programId) {
        return memberRepo.findByProgramIdAndRole(programId, ProgramRole.STAFF)
                .stream()
                .map(m -> m.getUser().getUsername())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    // -------------------------
    // VISIBILITY
    // -------------------------

    public enum Visibility { FULL, PUBLIC, NONE }

    @Transactional(readOnly = true)
    public Visibility visibilityFor(Program p, String usernameOrNull) {
        boolean publicVisible = (p.getState() == ProgramState.ANNOUNCED);

        if (usernameOrNull == null || usernameOrNull.isBlank()) {
            return publicVisible ? Visibility.PUBLIC : Visibility.NONE;
        }

        ProgramRole role = roleOf(p.getId(), usernameOrNull);
        if (role != null) return Visibility.FULL;

        return publicVisible ? Visibility.PUBLIC : Visibility.NONE;
    }

    @Transactional(readOnly = true)
    public Program getForView(Long programId, String usernameOrNull) {
        Program p = requireProgram(programId);
        Visibility v = visibilityFor(p, usernameOrNull);
        if (v == Visibility.NONE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND");
        }
        return p;
    }

    // -------------------------
    // COMMANDS
    // -------------------------

    @Transactional
    public Program create(String creatorUsername, String name, String description, LocalDate startDate, LocalDate endDate) {
        if (creatorUsername == null || creatorUsername.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        validateCreateOrUpdate(name, description, startDate, endDate);

        if (programRepo.existsByNameIgnoreCase(name.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_NAME_NOT_UNIQUE");
        }

        AppUser creator = userRepo.findByUsernameIgnoreCase(creatorUsername)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        Program p = new Program();
        p.setName(name.trim());
        p.setDescription(description.trim());
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setCreatedByUsername(creator.getUsername());

        Program saved = programRepo.save(p);

        ProgramMember m = new ProgramMember();
        m.setProgram(saved);
        m.setUser(creator);
        m.setRole(ProgramRole.PROGRAMMER);
        memberRepo.save(m);

        audit.log(creatorUsername, "PROGRAM_CREATE", saved.getId(), null, "created program");
        return saved;
    }

    @Transactional
    public Program update(Long programId, String actorUsername,
                          String name, String description, LocalDate startDate, LocalDate endDate) {
        if (actorUsername == null || actorUsername.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        requireProgrammer(programId, actorUsername);

        Program p = requireProgram(programId);
        ensureNotAnnounced(p);

        // apply only provided fields
        String newName = (name != null ? name.trim() : p.getName());
        String newDesc = (description != null ? description.trim() : p.getDescription());
        LocalDate newStart = (startDate != null ? startDate : p.getStartDate());
        LocalDate newEnd = (endDate != null ? endDate : p.getEndDate());

        validateCreateOrUpdate(newName, newDesc, newStart, newEnd);

        // unique name if changed
        if (!p.getName().equalsIgnoreCase(newName)) {
            programRepo.findByNameIgnoreCase(newName)
                    .ifPresent(other -> {
                        if (!other.getId().equals(p.getId())) {
                            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_NAME_NOT_UNIQUE");
                        }
                    });
        }

        p.setName(newName);
        p.setDescription(newDesc);
        p.setStartDate(newStart);
        p.setEndDate(newEnd);

        Program saved = programRepo.save(p);

        audit.log(actorUsername, "PROGRAM_UPDATE", programId, null, "updated fields");
        return saved;
    }

    @Transactional
    public void delete(Long programId, String actorUsername) {
        if (actorUsername == null || actorUsername.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        requireProgrammer(programId, actorUsername);

        Program p = requireProgram(programId);

        if (p.getState() != ProgramState.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_DELETE_ONLY_CREATED");
        }

        // για να μην “σπάσει” FK αν υπάρχουν screenings, το μπλοκάρουμε
        if (!screeningRepo.findByProgramId(programId).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_HAS_SCREENINGS");
        }

        // delete members first
        var members = memberRepo.findByProgramId(programId);
        memberRepo.deleteAll(members);

        programRepo.delete(p);

        audit.log(actorUsername, "PROGRAM_DELETE", programId, null, "deleted program in CREATED");
    }

    @Transactional
    public Program changeState(Long programId, String actorUsername, ProgramState target) {
        if (actorUsername == null || actorUsername.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        requireProgrammer(programId, actorUsername);

        Program p = requireProgram(programId);
        ProgramState current = p.getState();

        if (target == null) throw new ApiException(400, "STATE_REQUIRED");
        if (target == current) return p; // idempotent-ish

        if (!allowedTransition(current, target)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_STATE_TRANSITION_NOT_ALLOWED",
                    "Allowed only sequential forward transitions (now=" + current + ", target=" + target + ")");
        }

        // extra rule: when moving ASSIGNMENT->REVIEW, all SUBMITTED screenings must have handler
        if (current == ProgramState.ASSIGNMENT && target == ProgramState.REVIEW) {
            List<Screening> screenings = screeningRepo.findByProgramId(programId);
            boolean anyMissingHandler = screenings.stream()
                    .filter(s -> s.getState() == ScreeningState.SUBMITTED)
                    .anyMatch(s -> s.getHandler() == null);
            if (anyMissingHandler) {
                throw new ApiException(HttpStatus.CONFLICT, "HANDLER_ASSIGNMENT_INCOMPLETE");
            }
        }

        // auto-reject on FINAL_SUBMISSION -> DECISION:
        if (current == ProgramState.FINAL_SUBMISSION && target == ProgramState.DECISION) {
            List<Screening> autoReject = screeningRepo
                    .findByProgramIdAndStateAndFinalSubmittedAtIsNull(programId, ScreeningState.APPROVED);

            for (Screening s : autoReject) {
                s.setState(ScreeningState.REJECTED);
                s.setRejectionReason("AUTO_REJECTED_NOT_FINAL_SUBMITTED");
                screeningRepo.save(s);

                audit.log(actorUsername, "SCREENING_AUTO_REJECT", programId, s.getId(),
                        "Approved but not finally submitted -> REJECTED");
            }
        }

        p.setState(target);
        Program saved = programRepo.save(p);

        audit.log(actorUsername, "PROGRAM_STATE_CHANGE", programId, null, current + "->" + target);
        return saved;
    }

    // -------------------------
    // SEARCH
    // -------------------------

    @Transactional(readOnly = true)
    public List<Program> searchForUser(String username,
                                      String name,
                                      String description,
                                      LocalDate dateFrom,
                                      LocalDate dateTo,
                                      String filmTitle,
                                      String auditoriumName) {

        if (username == null || username.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");

        Specification<Program> spec = buildSearchSpec(name, description, dateFrom, dateTo, filmTitle, auditoriumName);

        Sort sort = Sort.by(Sort.Direction.ASC, "startDate")
                .and(Sort.by(Sort.Direction.ASC, "name"));

        List<Program> raw = programRepo.findAll(spec, sort);

        // role filter: if user is member -> see all of that program, else only ANNOUNCED
        List<ProgramMember> memberships = memberRepo.findByUserUsernameIgnoreCase(username);
        Set<Long> memberProgramIds = new HashSet<>();
        for (ProgramMember m : memberships) memberProgramIds.add(m.getProgram().getId());

        return raw.stream()
                .filter(p -> p.getState() == ProgramState.ANNOUNCED || memberProgramIds.contains(p.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Program> searchPublic(String name,
                                     String description,
                                     LocalDate dateFrom,
                                     LocalDate dateTo,
                                     String filmTitle,
                                     String auditoriumName) {

        Specification<Program> spec = buildSearchSpec(name, description, dateFrom, dateTo, filmTitle, auditoriumName)
                .and((root, q, cb) -> cb.equal(root.get("state"), ProgramState.ANNOUNCED));

        Sort sort = Sort.by(Sort.Direction.ASC, "startDate")
                .and(Sort.by(Sort.Direction.ASC, "name"));

        return programRepo.findAll(spec, sort);
    }

    @Transactional(readOnly = true)
    public Program getPublicProgram(Long programId) {
        Program p = requireProgram(programId);
        if (p.getState() != ProgramState.ANNOUNCED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND");
        }
        return p;
    }

    @Transactional(readOnly = true)
    public ProgramRole myRole(Long programId, String username) {
        return roleOf(programId, username);
    }
}
