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
    boolean read,
    String clientId   // echoed back from MessageRequest for client-side dedup
) {}
