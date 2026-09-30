package com.chatapp.dto;

public record AuthResponse(
    String token,
    Long userId,
    String username
) {}
