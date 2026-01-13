package com.example.demo.repo;

import com.example.demo.domain.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ProgramRepository extends JpaRepository<Program, Long>, JpaSpecificationExecutor<Program> {

    Optional<Program> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
