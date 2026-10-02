package com.chatapp.kafka;

import com.chatapp.dto.ChatMessageEvent;
import com.chatapp.model.Message;
import com.chatapp.model.User;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistenceConsumer {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    @KafkaListener(topics = "chat-messages")
    public void persistMessages(List<ChatMessageEvent> events) {
        List<Message> messages = events.stream().map(event -> {
            User sender = userRepository.getReferenceById(event.senderId());
            User receiver = userRepository.getReferenceById(event.receiverId());
            return Message.builder()
                    .sender(sender)
                    .receiver(receiver)
                    .content(event.content())
                    .build();
        }).toList();

        messageRepository.saveAll(messages);

        log.debug("Batch persisted {} messages", messages.size());
    }
}
