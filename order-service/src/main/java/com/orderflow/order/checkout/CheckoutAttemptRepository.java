package com.orderflow.order.checkout;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutAttemptRepository extends JpaRepository<CheckoutAttempt, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from CheckoutAttempt a where a.id = :id")
    Optional<CheckoutAttempt> findLocked(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<CheckoutAttempt> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
