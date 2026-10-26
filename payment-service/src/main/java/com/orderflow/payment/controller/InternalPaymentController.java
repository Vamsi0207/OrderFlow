package com.orderflow.payment.controller;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.enums.PaymentStatus;
import com.orderflow.payment.exception.PaymentNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;
/** Protected by exact subject, audience, purpose, expiry and payment-ID scoped JWT. */
@RestController @RequestMapping("/internal/v1/payments") @RequiredArgsConstructor
public class InternalPaymentController {
    private final PaymentRepository repository;
    @GetMapping("/{id}") public Status status(@PathVariable UUID id) {
        var p=repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id.toString()));
        return new Status(p.getId(),p.getOrderId(),p.getUserId(),p.getAmount(),p.getCurrency(),p.verifiedStatus());
    }
    public record Status(UUID id,UUID orderId,UUID userId,BigDecimal amount,String currency,PaymentStatus status) {}
}
