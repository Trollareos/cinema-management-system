package com.example.demo.service;

import com.example.demo.domain.AppUser;
import com.example.demo.domain.Program;
import com.example.demo.domain.ProgramMember;
import com.example.demo.domain.ProgramRole;
import com.example.demo.domain.ProgramState;
import com.example.demo.error.ApiException;
import com.example.demo.repo.ProgramMemberRepository;
import com.example.demo.repo.ProgramRepository;
import com.example.demo.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProgramMemberService {

    private final ProgramRepository programRepo;
    private final UserRepository userRepo;
    private final ProgramMemberRepository memberRepo;
    private final AuditLogService audit;

    public ProgramMemberService(
            ProgramRepository programRepo,
            UserRepository userRepo,
            ProgramMemberRepository memberRepo,
            AuditLogService audit
    ) {
        this.programRepo = programRepo;
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.audit = audit;
    }

    public ProgramRole getRole(Long programId, String username) {
        return memberRepo.findByProgramIdAndUserUsernameIgnoreCase(programId, username)
                .map(ProgramMember::getRole)
                .orElse(null);
    }

    public void requireProgrammer(Long programId, String username) {
        ProgramRole role = getRole(programId, username);
        if (role != ProgramRole.PROGRAMMER) {
            throw new AccessDeniedException("Only PROGRAMMER can manage members");
        }
    }

    public Program getProgramOrThrow(Long programId) {
        return programRepo.findById(programId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND"));
    }

    public AppUser getUserOrThrow(String username) {
        return userRepo.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<ProgramMember> listMembers(Long programId) {
        return memberRepo.findByProgramId(programId);
    }

    // ---- helpers ----

    private void ensureNotAnnounced(Program program) {
        if (program.getState() == ProgramState.ANNOUNCED) {
            throw new ApiException(HttpStatus.CONFLICT, "PROGRAM_LOCKED");
        }
    }

    /** STAFF set frozen after SUBMISSION (once you leave CREATED/SUBMISSION, cannot change staff). */
    private void ensureStaffMutable(Program program) {
        ProgramState st = program.getState();
        if (!(st == ProgramState.CREATED || st == ProgramState.SUBMISSION)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_SET_FROZEN");
        }
    }

    private void ensureCreatorNotRemoved(Program program, ProgramMember member) {
        String creator = program.getCreatedByUsername();
        if (creator != null
                && member.getRole() == ProgramRole.PROGRAMMER
                && member.getUser() != null
                && member.getUser().getUsername() != null
                && creator.equalsIgnoreCase(member.getUser().getUsername())) {
            throw new ApiException(HttpStatus.CONFLICT, "CREATOR_CANNOT_BE_REMOVED");
        }
    }

    private void ensureCreatorNotDemoted(Program program, ProgramMember member, ProgramRole newRole) {
        String creator = program.getCreatedByUsername();
        if (creator != null
                && member.getRole() == ProgramRole.PROGRAMMER
                && member.getUser() != null
                && member.getUser().getUsername() != null
                && creator.equalsIgnoreCase(member.getUser().getUsername())
                && newRole != ProgramRole.PROGRAMMER) {
            throw new ApiException(HttpStatus.CONFLICT, "CREATOR_MUST_REMAIN_PROGRAMMER");
        }
    }

    /** Για αμεροληψία: δεν επιτρέπουμε χειροκίνητη ανάθεση/αλλαγή σε SUBMITTER. */
    private void disallowManualSubmitterRole(ProgramRole role) {
        if (role == ProgramRole.SUBMITTER) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMITTER_ROLE_IS_AUTOMATIC");
        }
    }

    private void disallowSubmitterRoleChange(ProgramMember m, ProgramRole newRole) {
        if (m.getRole() == ProgramRole.SUBMITTER && newRole != ProgramRole.SUBMITTER) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMITTER_CANNOT_CHANGE_ROLE");
        }
        if (m.getRole() != ProgramRole.SUBMITTER && newRole == ProgramRole.SUBMITTER) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_ASSIGN_SUBMITTER_ROLE");
        }
    }

    // ---- main operations ----

    @Transactional
    public ProgramMember addProgrammer(Long programId, String actorUsername, String usernameToAdd) {
        return addMember(programId, actorUsername, usernameToAdd, ProgramRole.PROGRAMMER);
    }

    @Transactional
    public ProgramMember addStaff(Long programId, String actorUsername, String usernameToAdd) {
        Program program = getProgramOrThrow(programId);
        ensureNotAnnounced(program);
        ensureStaffMutable(program);
        return addMember(programId, actorUsername, usernameToAdd, ProgramRole.STAFF);
    }

    @Transactional
    public ProgramMember addMember(Long programId, String actorUsername, String usernameToAdd, ProgramRole roleToAdd) {
        disallowManualSubmitterRole(roleToAdd);

        Program program = getProgramOrThrow(programId);
        ensureNotAnnounced(program);

        if (roleToAdd == ProgramRole.STAFF) {
            ensureStaffMutable(program);
        }

        AppUser user = getUserOrThrow(usernameToAdd);

        if (memberRepo.existsByProgramIdAndUserUsernameIgnoreCase(programId, user.getUsername())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER");
        }

        ProgramMember m = new ProgramMember();
        m.setProgram(program);
        m.setUser(user);
        m.setRole(roleToAdd);

        ProgramMember saved = memberRepo.save(m);

        audit.log(actorUsername, "PROGRAM_MEMBER_ADD", programId, null,
                "added=" + user.getUsername() + " role=" + roleToAdd);

        return saved;
    }

    @Transactional
    public ProgramMember changeRole(Long programId, String actorUsername, Long memberId, ProgramRole newRole) {
        disallowManualSubmitterRole(newRole);

        ProgramMember m = memberRepo.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));

        if (!m.getProgram().getId().equals(programId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_IN_PROGRAM");
        }

        Program program = m.getProgram();
        ensureNotAnnounced(program);

        // submitter role cannot change (COI)
        disallowSubmitterRoleChange(m, newRole);

        // if staff is involved (old or new), enforce staff freeze rules
        if (m.getRole() == ProgramRole.STAFF || newRole == ProgramRole.STAFF) {
            ensureStaffMutable(program);
        }

        ensureCreatorNotDemoted(program, m, newRole);

        ProgramRole old = m.getRole();
        m.setRole(newRole);
        ProgramMember saved = memberRepo.save(m);

        audit.log(actorUsername, "PROGRAM_MEMBER_ROLE_CHANGE", programId, null,
                "user=" + saved.getUser().getUsername() + " " + old + "->" + newRole);

        return saved;
    }

    @Transactional
    public void removeMember(Long programId, String actorUsername, Long memberId) {
        ProgramMember m = memberRepo.findById(memberId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));

        if (!m.getProgram().getId().equals(programId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_NOT_IN_PROGRAM");
        }

        Program program = m.getProgram();
        ensureNotAnnounced(program);

        // submitter cannot be removed via member management (keeps COI simple)
        if (m.getRole() == ProgramRole.SUBMITTER) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REMOVE_SUBMITTER_MEMBER");
        }

        if (m.getRole() == ProgramRole.STAFF) {
            ensureStaffMutable(program);
        }

        ensureCreatorNotRemoved(program, m);

        String removedUser = (m.getUser() != null ? m.getUser().getUsername() : "unknown");
        ProgramRole removedRole = m.getRole();

        memberRepo.delete(m);

        audit.log(actorUsername, "PROGRAM_MEMBER_REMOVE", programId, null,
                "removed=" + removedUser + " role=" + removedRole);
    }
}
