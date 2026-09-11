package com.orderflow.payment.service;

import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.PaymentStatus;
import com.orderflow.payment.exception.PaymentNotFoundException;
import com.orderflow.payment.repository.PaymentRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StripePaymentService stripePaymentService;

    @Transactional
    public PaymentResponse createPayment(
            CreatePaymentRequest request,
            UUID authenticatedUserId) {

        PaymentResponse existingPayment = paymentRepository
                .findByOrderId(request.getOrderId())
                .map(this::mapToResponse)
                .orElse(null);

        if (existingPayment != null) {

            log.info(
                    "Duplicate payment request detected for order: {}. " +
                    "Returning existing payment: {}",
                    request.getOrderId(),
                    existingPayment.getId()
            );

            return existingPayment;
        }

        return processNewPayment(request, authenticatedUserId);
    }

    private PaymentResponse processNewPayment(
            CreatePaymentRequest request,
            UUID authenticatedUserId) {

        try {

            PaymentIntent paymentIntent =
                    stripePaymentService.createPaymentIntent(
                            request.getAmount(),
                            request.getOrderId()
                    );

            Payment payment = Payment.builder()
                    .orderId(request.getOrderId())
                    .userId(authenticatedUserId)
                    .amount(request.getAmount())
                    .status(PaymentStatus.PENDING)
                    .stripePaymentIntentId(paymentIntent.getId())
                    .build();

            Payment savedPayment = paymentRepository.save(payment);

            log.info(
                    "Payment created: {} for order: {} with Stripe PaymentIntent: {}",
                    savedPayment.getId(),
                    savedPayment.getOrderId(),
                    paymentIntent.getId()
            );

            return mapToResponse(
                    savedPayment,
                    paymentIntent.getClientSecret()
            );

        } catch (StripeException e) {

            log.error(
                    "Failed to create Stripe PaymentIntent for order: {}",
                    request.getOrderId(),
                    e
            );

            throw new RuntimeException(
                    "Failed to initialize payment with Stripe",
                    e
            );
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() ->
                        new PaymentNotFoundException(id.toString()));

        return mapToResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getAllPayments() {

        return paymentRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsByUser(UUID userId) {

        return paymentRepository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private PaymentResponse mapToResponse(Payment payment) {

        return mapToResponse(payment, null);
    }

    private PaymentResponse mapToResponse(
            Payment payment,
            String clientSecret) {

        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .stripePaymentIntentId(payment.getStripePaymentIntentId())
                .clientSecret(clientSecret)
                .build();
    }
}
