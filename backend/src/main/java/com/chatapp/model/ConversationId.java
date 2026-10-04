package com.chatapp.model;

import lombok.*;
import java.io.Serializable;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class ConversationId implements Serializable {
    private Long user1Id;
    private Long user2Id;
}
