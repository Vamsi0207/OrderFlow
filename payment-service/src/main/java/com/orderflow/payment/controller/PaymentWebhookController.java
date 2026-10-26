package com.orderflow.payment.controller;

import com.orderflow.payment.config.StripeProperties;
import com.orderflow.payment.service.PaymentWebhookService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookController {
    private final PaymentWebhookService paymentWebhookService;
    private final StripeProperties stripeProperties;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature) {
        final Event event;
        try {
            event=Webhook.constructEvent(payload,signature,stripeProperties.getWebhookSecret());
        } catch(SignatureVerificationException | RuntimeException invalid) {
            return ResponseEntity.badRequest().body("Invalid webhook signature or payload");
        }
        try {
            paymentWebhookService.handleEvent(event);
            return ResponseEntity.ok("Webhook processed");
        } catch(Exception retryable) {
            // Never log SDK exceptions, raw webhook data or client secrets.
            log.warn("Stripe webhook requires retry or reconciliation");
            return ResponseEntity.internalServerError().body("Webhook processing requires retry");
        }
    }
}
