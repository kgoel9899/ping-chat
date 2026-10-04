package com.chatapp.controller;

import com.chatapp.dto.*;
import com.chatapp.model.User;
import com.chatapp.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/conversation/{userId}")
    public ResponseEntity<CursorPageResponse<MessageResponse>> getConversation(
            @AuthenticationPrincipal User currentUser,
            @PathVariable Long userId,
            @RequestParam(required = false) Long cursor) {
        return ResponseEntity.ok(messageService.getConversation(currentUser.getId(), userId, cursor));
    }

    @GetMapping("/conversations")
    public ResponseEntity<CursorPageResponse<UserResponse>> getConversations(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(messageService.getConversations(currentUser.getId(), cursor));
    }

    // ── WebSocket STOMP endpoint ──
    @MessageMapping("chat.send")
    public void handleWsMessage(@Payload MessageRequest request, Principal principal) {
        User sender = (User) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        messageService.sendMessage(sender, request);
    }
}
