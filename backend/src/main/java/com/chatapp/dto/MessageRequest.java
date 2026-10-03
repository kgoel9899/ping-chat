package com.chatapp.dto;

import jakarta.validation.constraints.*;

public record MessageRequest(
    @NotNull Long receiverId,
    @NotBlank String content,
    String clientId   // client-generated UUID for dedup; optional (null for HTTP sends)
) {}
