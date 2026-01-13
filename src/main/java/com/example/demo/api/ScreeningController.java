package com.example.demo.api;

import com.example.demo.domain.Screening;
import com.example.demo.error.ApiException;
import com.example.demo.service.ScreeningService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ScreeningController {

    private final ScreeningService service;

    public ScreeningController(ScreeningService service) {
        this.service = service;
    }

    // ===== DTOs =====
    public record CreateScreeningRequest(Long programId) {}

    public record UpdateScreeningRequest(
            String filmTitle,
            String filmCast,
            String filmGenres,
            Integer filmDurationMinutes,
            String auditoriumName,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {}

    public record AssignHandlerRequest(String staffUsername) {}

    public record ReviewRequest(Integer score, String comments) {}

    public record ApproveRequest(String conditionalNotes) {}

    public record RejectRequest(String reason) {}

    public record FinalSubmitRequest(String finalBundleNotes) {}

    public record ScreeningPublicDto(
            Long id,
            Long programId,
            String filmTitle,
            String filmGenres,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String auditoriumName,
            String state
    ) {}

    public record ScreeningFullDto(
            Long id,
            Long programId,
            String state,

            String auditoriumName,
            String filmTitle,
            String filmCast,
            String filmGenres,
            Integer filmDurationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,

            Integer reviewScore,
            String reviewComments,

            String conditionalNotes,
            String rejectionReason,

            String submitterUsername,
            String handlerUsername,
            String finalSubmittedAt,
            String finalBundleNotes
    ) {}

    private Object toDto(Screening s, String meOrNull) {
        ScreeningService.Visibility v = service.visibilityFor(s, meOrNull);

        if (v == ScreeningService.Visibility.NONE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND");
        }

        if (v == ScreeningService.Visibility.FULL) {
            return new ScreeningFullDto(
                    s.getId(),
                    s.getProgram().getId(),
                    s.getState().name(),

                    s.getAuditoriumName(),
                    s.getFilmTitle(),
                    s.getFilmCast(),
                    s.getFilmGenres(),
                    s.getFilmDurationMinutes(),
                    s.getStartTime(),
                    s.getEndTime(),

                    s.getReviewScore(),
                    s.getReviewComments(),

                    s.getConditionalNotes(),
                    s.getRejectionReason(),

                    s.getSubmitter() != null ? s.getSubmitter().getUsername() : null,
                    s.getHandler() != null ? s.getHandler().getUsername() : null,
                    s.getFinalSubmittedAt() != null ? s.getFinalSubmittedAt().toString() : null,
                    s.getFinalBundleNotes()
            );
        }

        // PUBLIC
        return new ScreeningPublicDto(
                s.getId(),
                s.getProgram().getId(),
                s.getFilmTitle(),
                s.getFilmGenres(),
                s.getStartTime(),
                s.getEndTime(),
                s.getAuditoriumName(),
                s.getState().name()
        );
    }

    // CREATE
    @PostMapping("/screenings")
    public Object create(@RequestBody CreateScreeningRequest body, Authentication auth) {
        if (body == null || body.programId() == null) {
            throw new ApiException(400, "PROGRAM_ID_REQUIRED");
        }
        String me = auth.getName();
        Screening s = service.create(body.programId(), me);
        return toDto(s, me);
    }

    // UPDATE
    @PatchMapping("/screenings/{id}")
    public Object update(@PathVariable Long id, @RequestBody UpdateScreeningRequest body, Authentication auth) {
        String me = auth.getName();
        Screening s = service.update(
                id, me,
                body != null ? body.filmTitle() : null,
                body != null ? body.filmCast() : null,
                body != null ? body.filmGenres() : null,
                body != null ? body.filmDurationMinutes() : null,
                body != null ? body.auditoriumName() : null,
                body != null ? body.startTime() : null,
                body != null ? body.endTime() : null
        );
        return toDto(s, me);
    }

    // SUBMIT
    @PostMapping("/screenings/{id}/submit")
    public Object submit(@PathVariable Long id, Authentication auth) {
        String me = auth.getName();
        Screening s = service.submit(id, me);
        return toDto(s, me);
    }

    // WITHDRAW (delete if CREATED)
    @DeleteMapping("/screenings/{id}")
    public void withdraw(@PathVariable Long id, Authentication auth) {
        String me = auth.getName();
        service.withdraw(id, me);
    }

    // ASSIGN HANDLER
    @PostMapping("/screenings/{id}/assign-handler")
    public Object assignHandler(@PathVariable Long id, @RequestBody AssignHandlerRequest body, Authentication auth) {
        if (body == null || body.staffUsername() == null || body.staffUsername().trim().isEmpty()) {
            throw new ApiException(400, "STAFF_USERNAME_REQUIRED");
        }
        String me = auth.getName();
        Screening s = service.assignHandler(id, me, body.staffUsername().trim());
        return toDto(s, me);
    }

    // REVIEW
    @PostMapping("/screenings/{id}/review")
    public Object review(@PathVariable Long id, @RequestBody ReviewRequest body, Authentication auth) {
        String me = auth.getName();
        Integer score = body != null ? body.score() : null;
        String comments = body != null ? body.comments() : null;
        Screening s = service.review(id, me, score, comments);
        return toDto(s, me);
    }

    // APPROVE
    @PostMapping("/screenings/{id}/approve")
    public Object approve(@PathVariable Long id, @RequestBody ApproveRequest body, Authentication auth) {
        String me = auth.getName();
        String notes = body != null ? body.conditionalNotes() : null;
        Screening s = service.approve(id, me, notes);
        return toDto(s, me);
    }

    // REJECT
    @PostMapping("/screenings/{id}/reject")
    public Object reject(@PathVariable Long id, @RequestBody RejectRequest body, Authentication auth) {
        if (body == null || body.reason() == null || body.reason().trim().isEmpty()) {
            throw new ApiException(400, "REJECTION_REASON_REQUIRED");
        }
        String me = auth.getName();
        Screening s = service.reject(id, me, body.reason().trim());
        return toDto(s, me);
    }

    // FINAL SUBMIT
    @PostMapping("/screenings/{id}/final-submit")
    public Object finalSubmit(@PathVariable Long id, @RequestBody FinalSubmitRequest body, Authentication auth) {
        String me = auth.getName();
        String notes = body != null ? body.finalBundleNotes() : null;
        Screening s = service.finalSubmit(id, me, notes);
        return toDto(s, me);
    }

    // ACCEPT
    @PostMapping("/screenings/{id}/accept")
    public Object accept(@PathVariable Long id, Authentication auth) {
        String me = auth.getName();
        Screening s = service.acceptToSchedule(id, me);
        return toDto(s, me);
    }

    // VIEW (role-aware redaction)
    @GetMapping("/screenings/{id}")
    public Object view(@PathVariable Long id, Principal principal) {
        String me = (principal != null ? principal.getName() : null);
        Screening s = service.getForView(id, me);
        return toDto(s, me);
    }

    // SEARCH in a program
    @GetMapping("/programs/{programId}/screenings")
    public List<Object> search(
            @PathVariable Long programId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String cast,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTo,
            @RequestParam(required = false, defaultValue = "false") boolean timetable,
            Authentication auth
    ) {
        String me = auth.getName();
        return service.search(programId, me, title, cast, genre, startFrom, startTo, timetable)
                .stream()
                .map(s -> toDto(s, me))
                .toList();
    }
}
