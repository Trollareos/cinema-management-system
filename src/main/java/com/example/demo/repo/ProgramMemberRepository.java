package com.example.demo.repo;

import com.example.demo.domain.ProgramMember;
import com.example.demo.domain.ProgramRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramMemberRepository extends JpaRepository<ProgramMember, Long> {

    List<ProgramMember> findByProgramId(Long programId);

    List<ProgramMember> findByProgramIdAndRole(Long programId, ProgramRole role);

    Optional<ProgramMember> findByProgramIdAndUserUsernameIgnoreCase(Long programId, String username);

    boolean existsByProgramIdAndUserUsernameIgnoreCase(Long programId, String username);

    // NEW: για γρήγορο role-filtering στα search results
    List<ProgramMember> findByUserUsernameIgnoreCase(String username);
}
