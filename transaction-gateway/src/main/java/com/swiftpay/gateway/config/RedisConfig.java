package com.swiftpay.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    /**
     * Provides a {@link StringRedisTemplate} that stores both keys and values as
     * plain UTF-8 strings. Idempotency keys are simple transaction-ID strings, so
     * a String-based template is the most efficient choice (no serialisation overhead).
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
