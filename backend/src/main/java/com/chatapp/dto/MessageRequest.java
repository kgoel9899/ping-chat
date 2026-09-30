package com.chatapp.dto;

import jakarta.validation.constraints.*;

public record MessageRequest(
    @NotNull Long receiverId,
    @NotBlank String content
) {}
