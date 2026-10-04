package com.chatapp.service;

import com.chatapp.dto.*;
import com.chatapp.model.Message;
import com.chatapp.model.User;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.PageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    // ── Circuit breaker state ──
    // Once Kafka fails, kafkaDown flips to true. All user messages go straight to DB.
    // A background @Scheduled probe tests Kafka every 10s. On success, circuit closes.
    // The user's message-sending thread is NEVER blocked by Kafka timeouts.
    private final AtomicBoolean kafkaDown = new AtomicBoolean(false);

    public MessageResponse sendMessage(User sender, MessageRequest request) {
        log.info("Send message: sender={} -> receiver={}, length={}", sender.getId(), request.receiverId(), request.content().length());

        User receiver = userRepository.findById(request.receiverId())
                .orElseThrow(() -> {
                    log.warn("Receiver not found: id={}", request.receiverId());
                    return new RuntimeException("Receiver not found");
                });

        // Build response immediately (no DB write in hot path)
        LocalDateTime now = LocalDateTime.now();
        MessageResponse response = new MessageResponse(
                0L, // ID not available yet — DB write is async
                sender.getId(),
                sender.getUsername(),
                receiver.getId(),
                receiver.getUsername(),
                request.content(),
                now,
                false,
                request.clientId(), // echoed back so client can dedup by stable UUID
                request.imageUrl(),
                request.imageKey()
        );

        // Push to both users via WebSocket FIRST (instant delivery)
        messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/messages", response);
        messagingTemplate.convertAndSendToUser(receiver.getUsername(), "/queue/messages", response);

        // Persist: Kafka if healthy, direct DB if circuit is open
        ChatMessageEvent event = new ChatMessageEvent(
                sender.getId(), sender.getUsername(),
                receiver.getId(), receiver.getUsername(),
                request.content(),
                request.imageUrl(),
                request.imageKey()
        );

        if (kafkaDown.get()) {
            // Circuit OPEN — skip Kafka entirely, save to DB instantly
            persistDirectly(sender, receiver, event.content(), event.imageUrl(), event.imageKey());
        } else {
            // Circuit CLOSED — try Kafka
            try {
                kafkaTemplate.send("chat-messages", sender.getId().toString(), event).get();
                log.info("Message published to Kafka: sender={} -> receiver={}", sender.getId(), receiver.getId());
            } catch (Exception ex) {
                kafkaDown.set(true);
                log.warn("Kafka circuit OPEN — Kafka unreachable, all messages now go to direct DB: {}", ex.getMessage());
                persistDirectly(sender, receiver, event.content(), event.imageUrl(), event.imageKey());
            }
        }

        return response;
    }

    /**
     * Background probe: runs every 10 seconds. When the circuit is OPEN, sends a tiny
     * test message to Kafka to check if the broker has recovered. If it succeeds, closes
     * the circuit so normal Kafka flow resumes.
     *
     * This runs on Spring's scheduling thread — never on the user's message thread.
     * The user never waits for Kafka timeouts when the circuit is open.
     */
    @Scheduled(fixedDelay = 10_000)
    public void probeKafkaHealth() {
        if (!kafkaDown.get()) return; // circuit already closed, nothing to do

        log.info("Kafka circuit OPEN — background probe: testing Kafka connectivity...");
        try {
            // Send a health-check record. If the broker is up, this succeeds within delivery.timeout.ms.
            kafkaTemplate.send("chat-messages", "__probe__",
                    new ChatMessageEvent(0L, "__probe__", 0L, "__probe__", "__health_check__", null, null)).get();
            kafkaDown.set(false);
            log.info("Kafka circuit CLOSED — Kafka is back up, resuming normal async persistence");
        } catch (Exception ex) {
            log.warn("Kafka circuit still OPEN — probe failed: {}", ex.getMessage());
        }
    }

    /**
     * Fallback: persist message directly to the database when Kafka is unavailable.
     */
    private void persistDirectly(User sender, User receiver, String content, String imageUrl, String imageKey) {
        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .content(content)
                .imageUrl(imageUrl)
                .imageKey(imageKey)
                .build();
        messageRepository.save(message);
        log.info("DIRECT DB SAVE (Kafka fallback): message saved to database, sender={} -> receiver={}", sender.getId(), receiver.getId());
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
                message.isRead(),
                null, // DB-loaded messages have no clientId
                message.getImageUrl(),
                message.getImageKey()
        );
    }
}
