package com.chatapp.repository;

import com.chatapp.model.Conversation;
import com.chatapp.model.ConversationId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, ConversationId> {

    // Latest conversations for a user (no cursor)
    @Query("SELECT c FROM Conversation c WHERE c.user1Id = :userId OR c.user2Id = :userId ORDER BY c.lastMessageAt DESC")
    List<Conversation> findLatest(@Param("userId") Long userId, Pageable pageable);

    // Cursor-based: conversations older than cursor timestamp
    @Query("SELECT c FROM Conversation c WHERE (c.user1Id = :userId OR c.user2Id = :userId) AND c.lastMessageAt < :cursor ORDER BY c.lastMessageAt DESC")
    List<Conversation> findBefore(@Param("userId") Long userId, @Param("cursor") LocalDateTime cursor, Pageable pageable);

    // Upsert: insert or update last_message_at
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO conversations (user1_id, user2_id, last_message_at) " +
                   "VALUES (:user1Id, :user2Id, :ts) " +
                   "ON CONFLICT (user1_id, user2_id) DO UPDATE SET last_message_at = EXCLUDED.last_message_at",
           nativeQuery = true)
    void upsert(@Param("user1Id") Long user1Id, @Param("user2Id") Long user2Id, @Param("ts") LocalDateTime ts);
}
