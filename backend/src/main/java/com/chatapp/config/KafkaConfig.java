package com.chatapp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * If the topic doesn't exist when the app starts, Kafka would auto-create it with default settings
 * (usually 1 partition, 1 replica). By declaring it as a @Bean, Spring Kafka ensures it's created
 * with your settings (3 partitions) before any messages are sent. If the topic already exists, this is a no-op.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name("chat-messages")
                .partitions(3) // matches concurrency: 3 in yml
                .replicas(1) // Each partition has 1 replica — meaning 1 copy of the data.
                .build();
    }
}
