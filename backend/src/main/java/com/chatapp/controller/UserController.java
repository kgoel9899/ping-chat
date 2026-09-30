package com.chatapp.controller;

import com.chatapp.dto.UserResponse;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/search")
    public ResponseEntity<List<UserResponse>> searchUsers(@RequestParam String q) {
        return ResponseEntity.ok(
            userRepository.findByUsernameContainingIgnoreCase(q)
                .stream()
                .map(u -> new UserResponse(u.getId(), u.getUsername()))
                .toList()
        );
    }
}
