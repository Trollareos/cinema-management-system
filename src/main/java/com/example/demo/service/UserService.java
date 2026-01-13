package com.example.demo.service;

import com.example.demo.domain.AppUser;
import com.example.demo.error.ApiException;
import com.example.demo.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String username, String fullName, String rawPassword) {
        if (username == null || username.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USERNAME_REQUIRED");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FULLNAME_REQUIRED");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_REQUIRED");
        }
        if (rawPassword.length() < 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_SHORT");
        }

        String u = username.trim();

        if (userRepo.existsByUsernameIgnoreCase(u)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS");
        }

        AppUser user = new AppUser();
        user.setUsername(u);
        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);

        return userRepo.save(user);
    }

    public AppUser requireUser(String username) {
        return userRepo.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = userRepo.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                .roles("USER")
                .disabled(!u.isEnabled())
                .build();
    }
}
