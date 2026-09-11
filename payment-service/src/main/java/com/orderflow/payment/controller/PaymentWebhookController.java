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
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature) {

        try {

            Event event = Webhook.constructEvent(
                    payload,
                    signature,
                    stripeProperties.getWebhookSecret()
            );

            log.info(
                    "Received verified Stripe webhook: {}",
                    event.getType()
            );

            paymentWebhookService.handleEvent(event);

            return ResponseEntity.ok("Webhook processed");

        } catch (SignatureVerificationException e) {

            log.warn("Invalid Stripe webhook signature");

            return ResponseEntity.badRequest()
                    .body("Invalid webhook signature");

        } catch (Exception e) {

            log.error("Failed to process Stripe webhook", e);

            return ResponseEntity.internalServerError()
                    .body("Webhook processing failed");
        }
    }
}
