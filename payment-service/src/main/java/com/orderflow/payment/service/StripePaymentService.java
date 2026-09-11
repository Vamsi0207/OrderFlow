package com.orderflow.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@Slf4j
public class StripePaymentService {

    private static final String CURRENCY = "inr";

    public PaymentIntent createPaymentIntent(
            BigDecimal amount,
            UUID orderId) throws StripeException {

        long amountInPaise = amount
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        PaymentIntentCreateParams params =
                PaymentIntentCreateParams.builder()
                        .setAmount(amountInPaise)
                        .setCurrency(CURRENCY)
                        .putMetadata("orderId", orderId.toString())
                        .build();

        PaymentIntent paymentIntent = PaymentIntent.create(params);

        log.info(
                "Created Stripe PaymentIntent: {} for order: {}",
                paymentIntent.getId(),
                orderId
        );

        return paymentIntent;
    }
}
