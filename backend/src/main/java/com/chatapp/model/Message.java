package com.chatapp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_sender_ts",   columnList = "sender_id, timestamp DESC"),
    @Index(name = "idx_msg_receiver_ts", columnList = "receiver_id, timestamp DESC")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "image_key")
    private String imageKey;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Column(name = "is_read")
    private boolean read;

    @PrePersist
    protected void onCreate() {
        timestamp = LocalDateTime.now();
        read = false;
    }
}
