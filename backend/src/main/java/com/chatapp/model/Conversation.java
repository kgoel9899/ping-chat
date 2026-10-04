package com.chatapp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// @IdClass is JPA's way of declaring a composite primary key — a primary key made of more than one column.
// When an entity has multiple @Id fields, JPA requires you to provide a separate class that mirrors those fields
// so it can identify entities, run equality checks, and look them up by key.
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conv_user1_ts", columnList = "user1_id, last_message_at DESC"),
    @Index(name = "idx_conv_user2_ts", columnList = "user2_id, last_message_at DESC")
})
@IdClass(ConversationId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Conversation {

    @Id
    @Column(name = "user1_id")
    private Long user1Id;

    @Id
    @Column(name = "user2_id")
    private Long user2Id;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;
}
