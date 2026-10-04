package com.chatapp.dto;

import jakarta.validation.constraints.*;

public record MessageRequest(
    @NotNull Long receiverId,
    @NotBlank String content,
    String clientId,   // client-generated UUID for dedup; optional (null for HTTP sends)
    String imageUrl,   // presigned S3 download URL (null for text-only messages)
    String imageKey    // S3 object key for URL refresh (null for text-only messages)
) {}
