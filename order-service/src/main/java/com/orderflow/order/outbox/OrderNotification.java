package com.orderflow.order.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One immutable order.created message, committed in the order transaction. */
@Entity @Table(name="order_notifications", indexes=@Index(name="ix_order_notification_due",columnList="published,next_attempt_at"))
@Getter @Setter @NoArgsConstructor
public class OrderNotification {
    @Id private UUID orderId;
    @Column(nullable=false) private UUID userId;
    @Column(nullable=false,precision=10,scale=2) private BigDecimal amount;
    @Column(nullable=false) private boolean published;
    @Column(nullable=false) private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
}
