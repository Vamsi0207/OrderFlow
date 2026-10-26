package com.orderflow.payment.security;
import java.util.UUID;
public record PaymentActor(UUID userId, String role, String authorization) {
    public boolean admin() { return "ADMIN".equals(role); }
}
