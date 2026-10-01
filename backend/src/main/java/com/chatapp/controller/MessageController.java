package com.chatapp.controller;

import com.chatapp.dto.*;
import com.chatapp.model.User;
import com.chatapp.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(
            @AuthenticationPrincipal User sender,
            @Valid @RequestBody MessageRequest request) {
        return ResponseEntity.ok(messageService.sendMessage(sender, request));
    }

    @GetMapping("/conversation/{userId}")
    public ResponseEntity<List<MessageResponse>> getConversation(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long userId) {
        return ResponseEntity.ok(messageService.getConversation(currentUser.getId(), userId));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<UserResponse>> getConversations(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(messageService.getConversations(currentUser.getId()));
    }

    // ── WebSocket STOMP endpoint ──
    @MessageMapping("chat.send")
    public void handleWsMessage(@Payload MessageRequest request, Principal principal) {
        User sender = (User) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        messageService.sendMessage(sender, request);
    }
}
