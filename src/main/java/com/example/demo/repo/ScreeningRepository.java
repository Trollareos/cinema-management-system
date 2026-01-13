package com.example.demo.repo;

import com.example.demo.domain.Screening;
import com.example.demo.domain.ScreeningState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ScreeningRepository extends JpaRepository<Screening, Long>, JpaSpecificationExecutor<Screening> {

    List<Screening> findByProgramId(Long programId);

    List<Screening> findByProgramIdAndState(Long programId, ScreeningState state);

    List<Screening> findByProgramIdAndStateAndFinalSubmittedAtIsNull(Long programId, ScreeningState state);
}
