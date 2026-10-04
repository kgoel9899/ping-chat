package com.chatapp.imageservice.dto;

public record PresignedUploadResponse(
    String uploadUrl,    // presigned PUT URL — client uploads directly to S3
    String imageKey,     // S3 object key (e.g., "images/abc123-uuid.jpg")
    String downloadUrl   // presigned GET URL — embed in message for display
) {}
