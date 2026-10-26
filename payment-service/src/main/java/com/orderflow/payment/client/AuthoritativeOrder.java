package com.orderflow.payment.client;
import java.math.BigDecimal;
import java.util.UUID;
public record AuthoritativeOrder(UUID id, UUID userId, String status, BigDecimal totalAmount, String currency, Boolean paymentEligible) {
    public AuthoritativeOrder(UUID id,UUID userId,String status,BigDecimal totalAmount,String currency) {
        this(id,userId,status,totalAmount,currency,null);
    }
}
