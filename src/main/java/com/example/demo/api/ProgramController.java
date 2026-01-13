package com.example.demo.api;

import com.example.demo.domain.Program;
import com.example.demo.domain.ProgramRole;
import com.example.demo.domain.ProgramState;
import com.example.demo.error.ApiException;
import com.example.demo.service.ProgramService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/programs")
public class ProgramController {

    private final ProgramService programService;

    public ProgramController(ProgramService programService) {
        this.programService = programService;
    }

    // ===== DTOs =====
    public record ProgramCreateRequest(String name, String description, LocalDate startDate, LocalDate endDate) {}
    public record ProgramUpdateRequest(String name, String description, LocalDate startDate, LocalDate endDate) {}
    public record ProgramStateRequest(ProgramState state) {}

    public record ProgramPublicDto(
            Long id,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            List<String> programmers
    ) {}

    public record ProgramFullDto(
            Long id,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String state,
            String createdByUsername,
            LocalDateTime createdAt,
            List<String> programmers,
            List<String> staff,
            String myRole
    ) {}

    private Object toDto(Program p, String meOrNull) {
        ProgramService.Visibility v = programService.visibilityFor(p, meOrNull);
        if (v == ProgramService.Visibility.NONE) {
            throw new ApiException(404, "PROGRAM_NOT_FOUND");
        }

        List<String> programmers = programService.programmerUsernames(p.getId());

        if (v == ProgramService.Visibility.PUBLIC) {
            return new ProgramPublicDto(
                    p.getId(),
                    p.getName(),
                    p.getDescription(),
                    p.getStartDate(),
                    p.getEndDate(),
                    programmers
            );
        }

        List<String> staff = programService.staffUsernames(p.getId());
        ProgramRole myRole = (meOrNull != null ? programService.myRole(p.getId(), meOrNull) : null);

        return new ProgramFullDto(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getStartDate(),
                p.getEndDate(),
                p.getState().name(),
                p.getCreatedByUsername(),
                p.getCreatedAt(),
                programmers,
                staff,
                myRole != null ? myRole.name() : null
        );
    }

    // CREATE program (creator becomes PROGRAMMER automatically)
    @PostMapping
    public Object create(@RequestBody ProgramCreateRequest req, Authentication auth) {
        if (req == null) throw new ApiException(400, "BODY_REQUIRED");

        Program p = programService.create(
                auth.getName(),
                req.name(),
                req.description(),
                req.startDate(),
                req.endDate()
        );
        return toDto(p, auth.getName());
    }

    // UPDATE program (only PROGRAMMER, before ANNOUNCED)
    @PatchMapping("/{id}")
    public Object update(@PathVariable Long id, @RequestBody ProgramUpdateRequest req, Authentication auth) {
        if (req == null) throw new ApiException(400, "BODY_REQUIRED");

        Program p = programService.update(
                id,
                auth.getName(),
                req.name(),
                req.description(),
                req.startDate(),
                req.endDate()
        );
        return toDto(p, auth.getName());
    }

    // SEARCH programs with AND semantics + role filtering
    @GetMapping
    public List<Object> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String filmTitle,
            @RequestParam(required = false) String auditorium,
            Authentication auth
    ) {
        String me = auth.getName();

        return programService.searchForUser(me, name, description, dateFrom, dateTo, filmTitle, auditorium)
                .stream()
                .map(p -> toDto(p, me))
                .toList();
    }

    // VIEW (role-aware redaction)
    @GetMapping("/{id}")
    public Object view(@PathVariable Long id, Principal principal) {
        String me = (principal != null ? principal.getName() : null);
        Program p = programService.getForView(id, me);
        return toDto(p, me);
    }

    // DELETE (any PROGRAMMER, only if CREATED)
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Authentication auth) {
        programService.delete(id, auth.getName());
    }

    // STATE UPDATE (only PROGRAMMER, only forward allowed transitions)
    @PostMapping("/{id}/state")
    public Object changeState(@PathVariable Long id, @RequestBody ProgramStateRequest req, Authentication auth) {
        if (req == null || req.state() == null) throw new ApiException(400, "STATE_REQUIRED");

        Program p = programService.changeState(id, auth.getName(), req.state());
        return toDto(p, auth.getName());
    }
}
