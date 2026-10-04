package com.chatapp.dto;

public record ChatMessageEvent(
    Long senderId,
    String senderUsername,
    Long receiverId,
    String receiverUsername,
    String content,
    String imageUrl,
    String imageKey
) {}
