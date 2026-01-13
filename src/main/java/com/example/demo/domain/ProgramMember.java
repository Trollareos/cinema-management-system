package com.example.demo.domain;

import jakarta.persistence.*;

@Entity
@Table(
        name = "program_members",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_program_user", columnNames = {"program_id", "user_id"})
        }
)
public class ProgramMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id")
    private Program program;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProgramRole role;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Program getProgram() { return program; }
    public void setProgram(Program program) { this.program = program; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public ProgramRole getRole() { return role; }
    public void setRole(ProgramRole role) { this.role = role; }
}
