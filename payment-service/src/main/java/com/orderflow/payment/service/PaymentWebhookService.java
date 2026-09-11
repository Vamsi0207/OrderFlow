package com.orderflow.payment.service;

import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.PaymentStatus;
import com.orderflow.payment.event.PaymentCompletedEvent;
import com.orderflow.payment.event.PaymentFailedEvent;
import com.orderflow.payment.producer.PaymentEventProducer;
import com.orderflow.payment.repository.PaymentRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;

    @Transactional
    public void handleEvent(Event event) {

        switch (event.getType()) {

            case "payment_intent.succeeded" ->
                    handlePaymentSucceeded(event);

            case "payment_intent.payment_failed" ->
                    handlePaymentFailed(event);

            default ->
                    log.info(
                            "Ignoring unsupported Stripe event: {}",
                            event.getType()
                    );
        }
    }

    private void handlePaymentSucceeded(Event event) {

        PaymentIntent paymentIntent = deserializePaymentIntent(event);

        Payment payment = findPayment(paymentIntent);

        if (payment.getStatus() == PaymentStatus.COMPLETED) {

            log.info(
                    "Payment already completed. Ignoring duplicate event for PaymentIntent: {}",
                    paymentIntent.getId()
            );

            return;
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        PaymentCompletedEvent completedEvent =
                PaymentCompletedEvent.builder()
                        .paymentId(payment.getId())
                        .orderId(payment.getOrderId())
                        .userId(payment.getUserId())
                        .amount(payment.getAmount())
                        .build();

        paymentEventProducer.publishPaymentCompleted(completedEvent);

        log.info(
                "Payment completed through Stripe webhook. " +
                "Order: {}, PaymentIntent: {}",
                payment.getOrderId(),
                paymentIntent.getId()
        );
    }

    private void handlePaymentFailed(Event event) {

        PaymentIntent paymentIntent = deserializePaymentIntent(event);

        Payment payment = findPayment(paymentIntent);

        if (payment.getStatus() == PaymentStatus.FAILED) {

            log.info(
                    "Payment already failed. Ignoring duplicate event for PaymentIntent: {}",
                    paymentIntent.getId()
            );

            return;
        }

        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);

        PaymentFailedEvent failedEvent =
                PaymentFailedEvent.builder()
                        .paymentId(payment.getId())
                        .orderId(payment.getOrderId())
                        .userId(payment.getUserId())
                        .amount(payment.getAmount())
                        .build();

        paymentEventProducer.publishPaymentFailed(failedEvent);

        log.info(
                "Payment failed through Stripe webhook. " +
                "Order: {}, PaymentIntent: {}",
                payment.getOrderId(),
                paymentIntent.getId()
        );
    }

    private PaymentIntent deserializePaymentIntent(Event event) {

        try {

            return (PaymentIntent) event
                    .getDataObjectDeserializer()
                    .deserializeUnsafe();

        } catch (EventDataObjectDeserializationException e) {

            throw new IllegalStateException(
                    "Unable to deserialize PaymentIntent",
                    e
            );
        }
    }

    private Payment findPayment(PaymentIntent paymentIntent) {

        return paymentRepository
                .findByStripePaymentIntentId(paymentIntent.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Payment not found for Stripe PaymentIntent: "
                                        + paymentIntent.getId()
                        ));
    }
}
