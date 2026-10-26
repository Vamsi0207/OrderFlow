package com.orderflow.order.entity;
import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
/** Crash guard for quantity-only legacy inventory calls, not a distributed transaction. */
@Entity @Table(name="legacy_payment_effects") @IdClass(LegacyPaymentEffect.Key.class)
@Getter @Setter
public class LegacyPaymentEffect {
    @Id private UUID orderId;
    @Id private UUID productId;
    @Column(nullable=false) private UUID paymentId;
    @Column(nullable=false) private int quantity;
    @Column(nullable=false) private boolean completedPayment;
    @Column(nullable=false,length=16) private String state;
    @Column(nullable=false) private Instant updatedAt;
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {private UUID orderId;private UUID productId;}
}
