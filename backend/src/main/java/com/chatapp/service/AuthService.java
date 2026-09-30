package com.chatapp.service;

import com.chatapp.dto.*;
import com.chatapp.model.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponse register(RegisterRequest request) {
        log.info("Register attempt: username={}, email={}", request.username(), request.email());

        if (userRepository.existsByUsername(request.username())) {
            log.warn("Register failed: username '{}' already exists", request.username());
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            log.warn("Register failed: email '{}' already exists", request.email());
            throw new RuntimeException("Email already exists");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();
        user = userRepository.save(user);

        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        String token = jwtUtil.generateToken(user.getUsername());
        return new AuthResponse(token, user.getId(), user.getUsername());
    }

    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt: username={}", request.username());

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> {
                    log.warn("Login failed: user '{}' not found", request.username());
                    return new RuntimeException("Invalid credentials");
                });

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            log.warn("Login failed: bad password for user '{}'", request.username());
            throw new RuntimeException("Invalid credentials");
        }

        log.info("Login success: id={}, username={}", user.getId(), user.getUsername());
        String token = jwtUtil.generateToken(user.getUsername());
        return new AuthResponse(token, user.getId(), user.getUsername());
    }
}
