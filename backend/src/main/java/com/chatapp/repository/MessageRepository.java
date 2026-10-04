package com.chatapp.repository;

import com.chatapp.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Initial load — no cursor, get latest 15
    @Query("SELECT m FROM Message m WHERE " +
           "(m.sender.id = :userId1 AND m.receiver.id = :userId2) OR " +
           "(m.sender.id = :userId2 AND m.receiver.id = :userId1) " +
           "ORDER BY m.id DESC")
    List<Message> findConversationLatest(@Param("userId1") Long userId1, @Param("userId2") Long userId2, Pageable pageable);

    // Cursor pagination — get messages older than cursorId
    @Query("SELECT m FROM Message m WHERE " +
           "((m.sender.id = :userId1 AND m.receiver.id = :userId2) OR " +
           "(m.sender.id = :userId2 AND m.receiver.id = :userId1)) " +
           "AND m.id < :cursorId " +
           "ORDER BY m.id DESC")
    List<Message> findConversationBefore(@Param("userId1") Long userId1, @Param("userId2") Long userId2, @Param("cursorId") Long cursorId, Pageable pageable);
}
