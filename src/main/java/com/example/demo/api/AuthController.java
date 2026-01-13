package com.example.demo.api;

import com.example.demo.domain.AppUser;
import com.example.demo.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    // POST /api/auth/register
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest req) {
        AppUser saved = userService.register(req.username, req.fullName, req.password);
        UserResponse resp = new UserResponse(saved.getId(), saved.getUsername(), saved.getFullName());
        return ResponseEntity.created(URI.create("/api/users/" + saved.getId())).body(resp);
    }

    // GET /api/auth/me (auth required)
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        AppUser me = userService.requireUser(principal.getName());
        return ResponseEntity.ok(new MeResponse(me.getUsername(), me.getFullName()));
    }

    // ===== DTOs =====
    public static class RegisterRequest {
        @NotBlank
        public String username;

        @NotBlank
        public String fullName;

        @NotBlank
        @Size(min = 4, max = 100)
        public String password;
    }

    public static class UserResponse {
        public Long id;
        public String username;
        public String fullName;

        public UserResponse(Long id, String username, String fullName) {
            this.id = id;
            this.username = username;
            this.fullName = fullName;
        }
    }

    public static class MeResponse {
        public String username;
        public String fullName;

        public MeResponse(String username, String fullName) {
            this.username = username;
            this.fullName = fullName;
        }
    }
}
