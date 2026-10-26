package com.orderflow.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePaymentRequest {

    @NotNull
    private UUID orderId;

    /** Deprecated compatibility field. Ignored; amount and currency come from the order. */
    private BigDecimal amount;
}
