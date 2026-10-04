package com.chatapp.imageservice.dto;

public record PresignedDownloadResponse(
    String downloadUrl  // presigned GET URL for viewing the image
) {}
