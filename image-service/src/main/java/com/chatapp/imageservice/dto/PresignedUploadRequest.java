package com.chatapp.imageservice.dto;

import jakarta.validation.constraints.NotBlank;

public record PresignedUploadRequest(
    @NotBlank String filename,    // original filename (e.g., "photo.jpg")
    @NotBlank String contentType  // MIME type (e.g., "image/jpeg")
) {}
