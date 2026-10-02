package com.chatapp.service;

import com.chatapp.dto.*;
import com.chatapp.model.Message;
import com.chatapp.model.User;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.PageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public MessageResponse sendMessage(User sender, MessageRequest request) {
        log.info("Send message: sender={} -> receiver={}, length={}", sender.getId(), request.receiverId(), request.content().length());

        User receiver = userRepository.findById(request.receiverId())
                .orElseThrow(() -> {
                    log.warn("Receiver not found: id={}", request.receiverId());
                    return new RuntimeException("Receiver not found");
                });

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .content(request.content())
                .build();
        message = messageRepository.save(message);

        log.info("Message saved: id={}, sender={} -> receiver={}", message.getId(), sender.getId(), receiver.getId());
        MessageResponse response = toResponse(message);

        // Push to both users via WebSocket (silent no-op if not connected)
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", response);
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", response);

        return response;
    }

    public List<MessageResponse> getConversation(Long userId1, Long userId2, int page) {
        List<Message> msgs = messageRepository.findConversation(userId1, userId2, PageRequest.of(page, 15));
        Collections.reverse(msgs);
        log.info("Get conversation: user1={}, user2={}, page={}, messages={}", userId1, userId2, page, msgs.size());
        return msgs.stream().map(this::toResponse).toList();
    }

    public List<UserResponse> getConversations(Long userId, int page) {
        List<Long> partnerIds = messageRepository.findConversationPartnerIds(userId, PageRequest.of(page, 15));
        log.info("Get conversations: user={}, page={}, partnerIds={}", userId, page, partnerIds);
        return userRepository.findAllById(partnerIds).stream()
                .map(u -> new UserResponse(u.getId(), u.getUsername()))
                .toList();
    }

    private MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getReceiver().getId(),
                message.getReceiver().getUsername(),
                message.getContent(),
                message.getTimestamp(),
                message.isRead()
        );
    }
}
