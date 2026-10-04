package com.chatapp.kafka;

import com.chatapp.dto.ChatMessageEvent;
import com.chatapp.model.Message;
import com.chatapp.model.User;
import com.chatapp.repository.ConversationRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistenceConsumer {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    // It isn't called from anywhere in the code — the @KafkaListener annotation means Spring Kafka calls it automatically whenever messages arrive on the "chat-messages" topic.
    @KafkaListener(topics = "chat-messages")
    public void persistMessages(List<ChatMessageEvent> events) {
        // Filter out health-check probe messages sent by the circuit breaker
        List<ChatMessageEvent> realEvents = events.stream()
                .filter(e -> !"__probe__".equals(e.senderUsername()))
                .toList();

        if (realEvents.isEmpty()) {
            log.debug("Kafka batch contained only probe messages, skipping DB write");
            return;
        }

        log.info("KAFKA CONSUMER: Received batch of {} messages from topic", realEvents.size());

        List<Message> messages = realEvents.stream().map(event -> {
            // getReferenceById returns a proxy with only the ID set — no DB query.
            // Hibernate only needs the ID to write the FK in the INSERT, so a full findById would be wasteful.
            User sender = userRepository.getReferenceById(event.senderId());
            User receiver = userRepository.getReferenceById(event.receiverId());
            return Message.builder()
                    .sender(sender)
                    .receiver(receiver)
                    .content(event.content())
                    .imageUrl(event.imageUrl())
                    .imageKey(event.imageKey())
                    .build();
        }).toList();

        messageRepository.saveAll(messages);

        // Upsert conversations table — one row per unique pair
        LocalDateTime now = LocalDateTime.now();
        realEvents.forEach(event -> {
            Long u1 = Math.min(event.senderId(), event.receiverId());
            Long u2 = Math.max(event.senderId(), event.receiverId());
            conversationRepository.upsert(u1, u2, now);
        });

        log.info("DATABASE SAVE: Batch persisted {} messages to PostgreSQL", messages.size());
        realEvents.forEach(event ->
            log.info("  -> Saved message: sender={} ({}) -> receiver={} ({}), contentLength={}",
                event.senderId(), event.senderUsername(),
                event.receiverId(), event.receiverUsername(),
                event.content().length())
        );
    }
}
