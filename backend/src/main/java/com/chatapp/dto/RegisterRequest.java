package com.chatapp.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
    @NotBlank String username,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 6) String password
) {}
