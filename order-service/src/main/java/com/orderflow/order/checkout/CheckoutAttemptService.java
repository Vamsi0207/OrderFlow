package com.orderflow.order.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutAttemptService {
    private final JdbcTemplate jdbc;
    private final CheckoutAttemptRepository repository;

    /** Commit intent independently before remote side effects. PostgreSQL arbitrates insert races.
     * ON CONFLICT waits for an uncommitted winner; a failed winner allows this insert to succeed.
     * No constraint exception is caught inside a rollback-only JPA transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CheckoutAttempt begin(UUID userId, String key, CheckoutRequest request) {
        if (userId == null || key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("User and idempotency key (1-128 characters) are required");
        var payload = CheckoutPayload.from(request);
        jdbc.update("""
                INSERT INTO checkout_attempts
                (id, user_id, idempotency_key, payload_hash, normalized_payload, status,
                 retry_count, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'CREATED', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), userId, key, payload.hash(), payload.normalized());
        var attempt = repository.findByUserIdAndIdempotencyKey(userId, key).orElseThrow();
        if (!attempt.getPayloadHash().equals(payload.hash()) ||
                !attempt.getNormalizedPayload().equals(payload.normalized()))
            throw new CheckoutConflictException();
        return attempt;
    }
}
