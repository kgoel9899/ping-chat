package com.chatapp.dto;

import java.time.LocalDateTime;

public record MessageResponse(
    Long id,
    Long senderId,
    String senderUsername,
    Long receiverId,
    String receiverUsername,
    String content,
    LocalDateTime timestamp,
    String clientId,   // echoed back from MessageRequest for client-side dedup
    String imageUrl,   // presigned S3 download URL (null for text-only messages)
    String imageKey    // S3 object key for URL refresh (null for text-only messages)
) {}
