package com.chatapp.repository;

import com.chatapp.model.Message;
import com.chatapp.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE " +
           "(m.sender.id = :userId1 AND m.receiver.id = :userId2) OR " +
           "(m.sender.id = :userId2 AND m.receiver.id = :userId1) " +
           "ORDER BY m.timestamp DESC")
    List<Message> findConversation(@Param("userId1") Long userId1, @Param("userId2") Long userId2, Pageable pageable);

    @Query(value = "SELECT partner_id FROM (" +
           "SELECT CASE WHEN m.sender_id = :userId THEN m.receiver_id ELSE m.sender_id END AS partner_id, " +
           "MAX(m.timestamp) AS last_message " +
           "FROM messages m WHERE m.sender_id = :userId OR m.receiver_id = :userId " +
           "GROUP BY partner_id " +
           "ORDER BY last_message DESC) sub", nativeQuery = true)
    List<Long> findConversationPartnerIds(@Param("userId") Long userId, Pageable pageable);
}
