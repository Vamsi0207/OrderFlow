package com.orderflow.order.checkout;

import com.orderflow.order.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_attempts", uniqueConstraints = @UniqueConstraint(
        name = "uk_checkout_user_key", columnNames = {"user_id", "idempotency_key"}),
        indexes = @Index(name = "ix_checkout_recovery", columnList = "status,next_retry_at"))
@Getter
@Setter
public class CheckoutAttempt extends BaseEntity {
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;
    @Column(nullable = false, length = 64, updatable = false)
    private String payloadHash;
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String normalizedPayload;
    private UUID orderId;
    private UUID paymentId;
    private String paymentInitializationState;
    private String paymentStatus;
    private Integer lastHttpStatus;
    private UUID leaseOwner;
    private Instant leaseUntil;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;
    private String lastError;
    private Instant nextRetryAt;
    @Column(nullable = false)
    private int retryCount;
    @Version
    private long version;

    public enum Status { CREATED, ORDER_CREATED, INVENTORY_RESERVED, COMPLETED, RECOVERY_REQUIRED }
}
