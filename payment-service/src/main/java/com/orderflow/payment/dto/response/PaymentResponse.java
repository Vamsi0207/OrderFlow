package com.orderflow.payment.dto.response;

import com.orderflow.payment.enums.PaymentStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponse {

    private UUID id;
    private UUID orderId;
    private UUID userId;
    private BigDecimal amount;
    private PaymentStatus status;
    private Instant createdAt;

    private String stripePaymentIntentId;
    private String clientSecret;
}
