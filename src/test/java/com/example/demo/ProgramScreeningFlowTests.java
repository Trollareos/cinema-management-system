package com.example.demo;

import com.example.demo.domain.*;
import com.example.demo.service.ProgramMemberService;
import com.example.demo.service.ProgramService;
import com.example.demo.service.ScreeningService;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ProgramScreeningFlowTests {

    @Autowired private UserService userService;
    @Autowired private ProgramService programService;
    @Autowired private ProgramMemberService memberService;
    @Autowired private ScreeningService screeningService;

    private static String u(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void happyPath_programAndScreeningFullFlow() {

        String programmer = u("prog");
        String staff = u("staff");
        String submitter = u("sub");

        userService.register(programmer, "pass1234", "Programmer User");
        userService.register(staff, "pass1234", "Staff User");
        userService.register(submitter, "pass1234", "Submitter User");


        Program p = programService.create(
                programmer,
                "Season " + u("S"),
                "Test season",
                LocalDate.now(),
                LocalDate.now().plusDays(2)
        );

        Long programId = p.getId();
        assertNotNull(programId);

        // add staff while CREATED
        memberService.addStaff(programId, programmer, staff);

        // move to SUBMISSION
        p = programService.changeState(programId, programmer, ProgramState.SUBMISSION);
        assertEquals(ProgramState.SUBMISSION, p.getState());

        // create screening
        Screening s = screeningService.create(programId, submitter);

        // fill details + startTime (so it can be scheduled later)
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);

        s = screeningService.update(
                s.getId(),
                submitter,
                "My Film",
                "Actor A, Actor B",
                "Drama",
                90,
                "Hall 1",
                start,
                null
        );

        // submit screening
        s = screeningService.submit(s.getId(), submitter);
        assertEquals(ScreeningState.SUBMITTED, s.getState());

        // ASSIGNMENT
        p = programService.changeState(programId, programmer, ProgramState.ASSIGNMENT);
        assertEquals(ProgramState.ASSIGNMENT, p.getState());

        s = screeningService.assignHandler(s.getId(), programmer, staff);
        assertNotNull(s.getHandler());

        // REVIEW
        p = programService.changeState(programId, programmer, ProgramState.REVIEW);
        s = screeningService.review(s.getId(), staff, 8, "Looks good");
        assertEquals(ScreeningState.REVIEWED, s.getState());

        // SCHEDULING
        p = programService.changeState(programId, programmer, ProgramState.SCHEDULING);
        s = screeningService.approve(s.getId(), submitter, "Minor changes requested");
        assertEquals(ScreeningState.APPROVED, s.getState());

        // FINAL_SUBMISSION
        p = programService.changeState(programId, programmer, ProgramState.FINAL_SUBMISSION);
        s = screeningService.finalSubmit(s.getId(), submitter, "Final bundle ok");
        assertNotNull(s.getFinalSubmittedAt());

        // DECISION
        p = programService.changeState(programId, programmer, ProgramState.DECISION);

        // ACCEPT to schedule
        s = screeningService.acceptToSchedule(s.getId(), programmer);
        assertEquals(ScreeningState.SCHEDULED, s.getState());

        // ANNOUNCED
        p = programService.changeState(programId, programmer, ProgramState.ANNOUNCED);
        assertEquals(ProgramState.ANNOUNCED, p.getState());

        // Visitor can view scheduled screening (no auth)
        Screening publicView = screeningService.getForView(s.getId(), null);
        assertNotNull(publicView);
        assertEquals(ScreeningState.SCHEDULED, publicView.getState());
    }

    @Test
    void autoReject_inDecision_ifApprovedNotFinallySubmitted() {

        String programmer = u("prog");
        String staff = u("staff");
        String submitter = u("sub");

        userService.register(programmer, "pass1234", "Programmer User");
        userService.register(staff, "pass1234", "Staff User");
        userService.register(submitter, "pass1234", "Submitter User");


        Program p = programService.create(
                programmer,
                "Season " + u("S"),
                "Auto reject test",
                LocalDate.now(),
                LocalDate.now().plusDays(2)
        );
        Long programId = p.getId();

        memberService.addStaff(programId, programmer, staff);

        // SUBMISSION
        programService.changeState(programId, programmer, ProgramState.SUBMISSION);

        // Screening A (will final submit)
        Screening a = screeningService.create(programId, submitter);
        LocalDateTime startA = LocalDateTime.now().plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0);
        a = screeningService.update(a.getId(), submitter, "Film A", "Cast A", "Action", 100, "Hall 1", startA, null);
        a = screeningService.submit(a.getId(), submitter);

        // Screening B (will NOT final submit)
        Screening b = screeningService.create(programId, submitter);
        LocalDateTime startB = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        b = screeningService.update(b.getId(), submitter, "Film B", "Cast B", "Comedy", 80, "Hall 2", startB, null);
        b = screeningService.submit(b.getId(), submitter);

        // ASSIGNMENT
        programService.changeState(programId, programmer, ProgramState.ASSIGNMENT);
        a = screeningService.assignHandler(a.getId(), programmer, staff);
        b = screeningService.assignHandler(b.getId(), programmer, staff);

        // REVIEW
        programService.changeState(programId, programmer, ProgramState.REVIEW);
        a = screeningService.review(a.getId(), staff, 7, "ok");
        b = screeningService.review(b.getId(), staff, 7, "ok");

        // SCHEDULING
        programService.changeState(programId, programmer, ProgramState.SCHEDULING);
        a = screeningService.approve(a.getId(), submitter, null);
        b = screeningService.approve(b.getId(), submitter, null);

        // FINAL_SUBMISSION
        programService.changeState(programId, programmer, ProgramState.FINAL_SUBMISSION);
        a = screeningService.finalSubmit(a.getId(), submitter, "done");
        assertNotNull(a.getFinalSubmittedAt());
        assertNull(b.getFinalSubmittedAt());

        // DECISION (triggers auto-reject for approved not finally submitted)
        programService.changeState(programId, programmer, ProgramState.DECISION);

        Screening bAfter = screeningService.getForView(b.getId(), submitter);
        assertEquals(ScreeningState.REJECTED, bAfter.getState());
        assertEquals("AUTO_REJECTED_NOT_FINAL_SUBMITTED", bAfter.getRejectionReason());
    }
}
