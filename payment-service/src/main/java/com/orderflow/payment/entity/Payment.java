package com.orderflow.payment.entity;

import com.orderflow.payment.enums.PaymentStatus;
import com.orderflow.payment.enums.InitializationState;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "payments",
        indexes = {
                @Index(name = "ix_payment_initialization", columnList = "initialization_state,initialization_started_at"),
                @Index(name = "ix_payment_notification", columnList = "event_published,pending_event"),
                @Index(name = "ix_payment_recovery_due",columnList="recovery_next_at,recovery_attempts")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_order_id",
                        columnNames = "order_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment extends BaseEntity {

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false, length = 3, columnDefinition = "varchar(3) default 'INR'")
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    private InitializationState initializationState;
    private Instant initializationStartedAt;
    private String lastInitializationError;
    private String pendingEvent;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean terminalVerified;
    public PaymentStatus verifiedStatus() { return terminalVerified ? status : PaymentStatus.PENDING; }
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean eventPublished;
    @Column(nullable=false,columnDefinition="integer default 0")
    private int recoveryAttempts;
    private Instant recoveryNextAt;
    private UUID recoveryToken;
    private String recoveryError;
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Column(name = "stripe_payment_intent_id", unique = true)
    private String stripePaymentIntentId;
}
