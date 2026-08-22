package com.orderflow.payment.event;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentCompletedEvent {

    private UUID paymentId;

    private UUID orderId;

    private UUID userId;

    private BigDecimal amount;
}
