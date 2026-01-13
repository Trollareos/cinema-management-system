package com.example.demo.api;

import com.example.demo.domain.ProgramMember;
import com.example.demo.domain.ProgramRole;
import com.example.demo.error.ApiException;
import com.example.demo.service.ProgramMemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/programs/{programId}/members")
public class ProgramMemberController {

    private final ProgramMemberService memberService;

    public ProgramMemberController(ProgramMemberService memberService) {
        this.memberService = memberService;
    }

    public record AddUserRequest(String username) {}
    public record ChangeRoleRequest(ProgramRole role) {}
    public record MemberResponse(String username, ProgramRole role) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<MemberResponse> list(@PathVariable Long programId, Authentication auth) {
        memberService.requireProgrammer(programId, auth.getName());

        List<ProgramMember> members = memberService.listMembers(programId);
        return members.stream()
                .map(m -> new MemberResponse(m.getUser().getUsername(), m.getRole()))
                .toList();
    }

    @PostMapping("/programmers")
    public ResponseEntity<MemberResponse> addProgrammer(
            @PathVariable Long programId,
            @RequestBody AddUserRequest req,
            Authentication auth
    ) {
        memberService.requireProgrammer(programId, auth.getName());

        if (req == null || req.username() == null || req.username().trim().isEmpty()) {
            throw new ApiException(400, "USERNAME_REQUIRED");
        }

        ProgramMember m = memberService.addProgrammer(programId, auth.getName(), req.username().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MemberResponse(m.getUser().getUsername(), m.getRole()));
    }

    @PostMapping("/staff")
    public ResponseEntity<MemberResponse> addStaff(
            @PathVariable Long programId,
            @RequestBody AddUserRequest req,
            Authentication auth
    ) {
        memberService.requireProgrammer(programId, auth.getName());

        if (req == null || req.username() == null || req.username().trim().isEmpty()) {
            throw new ApiException(400, "USERNAME_REQUIRED");
        }

        ProgramMember m = memberService.addStaff(programId, auth.getName(), req.username().trim());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MemberResponse(m.getUser().getUsername(), m.getRole()));
    }

    @PatchMapping("/{memberId}/role")
    public ResponseEntity<MemberResponse> changeRole(
            @PathVariable Long programId,
            @PathVariable Long memberId,
            @RequestBody ChangeRoleRequest req,
            Authentication auth
    ) {
        memberService.requireProgrammer(programId, auth.getName());

        if (req == null || req.role() == null) {
            throw new ApiException(400, "ROLE_REQUIRED");
        }

        ProgramMember m = memberService.changeRole(programId, auth.getName(), memberId, req.role());
        return ResponseEntity.ok(new MemberResponse(m.getUser().getUsername(), m.getRole()));
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> remove(
            @PathVariable Long programId,
            @PathVariable Long memberId,
            Authentication auth
    ) {
        memberService.requireProgrammer(programId, auth.getName());
        memberService.removeMember(programId, auth.getName(), memberId);
        return ResponseEntity.noContent().build();
    }
}
