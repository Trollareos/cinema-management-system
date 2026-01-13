package com.example.demo.api;

import com.example.demo.domain.Program;
import com.example.demo.domain.Screening;
import com.example.demo.service.ProgramService;
import com.example.demo.service.ScreeningService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final ProgramService programService;
    private final ScreeningService screeningService;

    public PublicController(ProgramService programService, ScreeningService screeningService) {
        this.programService = programService;
        this.screeningService = screeningService;
    }

    public record ProgramPublicDto(
            Long id,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            List<String> programmers
    ) {}

    public record ScreeningPublicDto(
            Long id,
            Long programId,
            String filmTitle,
            String filmGenres,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String auditoriumName
    ) {}

    // VISITOR program search (only ANNOUNCED) with AND semantics
    @GetMapping("/programs")
    public List<ProgramPublicDto> announcedPrograms(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String filmTitle,
            @RequestParam(required = false) String auditorium
    ) {
        return programService.searchPublic(name, description, dateFrom, dateTo, filmTitle, auditorium)
                .stream()
                .map(p -> new ProgramPublicDto(
                        p.getId(),
                        p.getName(),
                        p.getDescription(),
                        p.getStartDate(),
                        p.getEndDate(),
                        programService.programmerUsernames(p.getId())
                ))
                .toList();
    }

    // VISITOR program view (only ANNOUNCED)
    @GetMapping("/programs/{id}")
    public ProgramPublicDto programById(@PathVariable Long id) {
        Program p = programService.getPublicProgram(id);
        return new ProgramPublicDto(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getStartDate(),
                p.getEndDate(),
                programService.programmerUsernames(p.getId())
        );
    }

    // VISITOR screening search inside announced program (only SCHEDULED are visible)
    @GetMapping("/programs/{id}/screenings")
    public List<ScreeningPublicDto> screenings(
            @PathVariable Long id,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String cast,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTo,
            @RequestParam(required = false, defaultValue = "true") boolean timetable
    ) {
        List<Screening> list = screeningService.search(id, null, title, cast, genre, startFrom, startTo, timetable);

        // επειδή usernameOrNull=null, το ScreeningService θα κρατήσει ΜΟΝΟ public-visible (ANNOUNCED + SCHEDULED)
        return list.stream()
                .map(s -> new ScreeningPublicDto(
                        s.getId(),
                        s.getProgram().getId(),
                        s.getFilmTitle(),
                        s.getFilmGenres(),
                        s.getStartTime(),
                        s.getEndTime(),
                        s.getAuditoriumName()
                ))
                .toList();
    }
}
