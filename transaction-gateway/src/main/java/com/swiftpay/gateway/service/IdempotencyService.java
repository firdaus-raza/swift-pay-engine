package com.swiftpay.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Provides distributed idempotency guarantees using Redis.
 *
 * <p>Strategy: atomic {@code SET key value NX EX ttlSeconds}
 * <ul>
 *   <li><b>NX</b> – only sets the key if it does NOT already exist (atomic check-and-set).</li>
 *   <li><b>EX</b> – automatically expires the key after {@value #IDEMPOTENCY_TTL_HOURS} hours,
 *       satisfying the 24-hour uniqueness window stated in the requirements.</li>
 * </ul>
 *
 * <p>This approach is race-condition-safe even under high concurrency because Redis processes
 * the SET NX EX command atomically — no two callers can both receive {@code true} for the
 * same transaction ID within the TTL window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    /** Key prefix – makes Redis keys identifiable in monitoring tools (e.g. RedisInsight). */
    private static final String KEY_PREFIX = "swiftpay:idempotency:";

    /** Idempotency window as required by the specification. */
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    /**
     * Attempts to acquire an idempotency lock for the given transaction ID.
     *
     * @param transactionId the unique transaction identifier supplied by the caller
     * @return {@code true} if the lock was acquired (first time seen within the TTL window);
     *         {@code false} if the transaction ID was already processed (duplicate request)
     */
    public boolean lock(UUID transactionId) {
        String key = KEY_PREFIX + transactionId.toString();
        Duration ttl = Duration.ofHours(IDEMPOTENCY_TTL_HOURS);

        // Boolean.TRUE means the key was newly set (lock acquired).
        // null / Boolean.FALSE means the key already existed (duplicate).
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "LOCKED", ttl);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Idempotency lock acquired for transaction: {} (TTL={}h)", transactionId, IDEMPOTENCY_TTL_HOURS);
            return true;
        }

        log.warn("Idempotency lock REJECTED – transaction already seen within {}h window: {}", IDEMPOTENCY_TTL_HOURS, transactionId);
        return false;
    }

    /**
     * Releases the idempotency lock for the given transaction ID.
     *
     * <p><b>Note:</b> This is used in failure/rollback scenarios so that a legitimately
     * retried request (after a transient error) is not permanently blocked. In a happy-path
     * scenario the key is intentionally kept in Redis for the full TTL window to prevent
     * duplicate processing from any client.
     *
     * @param transactionId the transaction ID whose lock should be released
     */
    public void unlock(UUID transactionId) {
        String key = KEY_PREFIX + transactionId.toString();
        redisTemplate.delete(key);
        log.debug("Idempotency lock released for transaction: {}", transactionId);
    }

    /**
     * Returns the remaining TTL (in seconds) of an existing idempotency key.
     * Useful for debugging and health checks.
     *
     * @param transactionId the transaction ID to inspect
     * @return remaining TTL in seconds, or {@code -2} if the key does not exist
     */
    public long getRemainingTtlSeconds(UUID transactionId) {
        String key = KEY_PREFIX + transactionId.toString();
        Long ttl = redisTemplate.getExpire(key);
        return ttl != null ? ttl : -2L;
    }
}
