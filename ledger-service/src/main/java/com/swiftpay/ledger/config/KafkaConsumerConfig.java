package com.swiftpay.ledger.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configuration class for customization of Kafka message consumers.
 * Configures the container factories with custom error handling policies to provide resilience.
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    /**
     * Configures the Kafka listener container factory to use a common error handler
     * that retries processing of failed messages (e.g., database connection down or optimistic locks).
     *
     * Strategy:
     * - Retries the message 3 times (1 initial execution + 2 retries).
     * - Delays 2 seconds between retries to allow brief database interruptions to recover.
     * - If processing still fails after the maximum retries, the error handler logs a description and continues.
     *
     * @param consumerFactory the default consumer factory supplied by Spring Boot
     * @return the configured ConcurrentKafkaListenerContainerFactory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Define a fixed backoff: 2000 milliseconds interval, max 2 retry attempts (3 executions total)
        FixedBackOff backOff = new FixedBackOff(2000L, 2L);
        
        // Construct the default error handler with the backoff policy and a logging fallback recovery block
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> 
            log.error("Failed to process event after retries. Topic: {}, Partition: {}, Offset: {}, Key: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception), 
            backOff
        );

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
