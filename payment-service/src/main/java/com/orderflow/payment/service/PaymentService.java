package com.orderflow.payment.service;

import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.PaymentStatus;
import com.orderflow.payment.event.PaymentCompletedEvent;
import com.orderflow.payment.event.PaymentFailedEvent;
import com.orderflow.payment.exception.PaymentNotFoundException;
import com.orderflow.payment.producer.PaymentEventProducer;
import com.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;

    @Transactional
    public PaymentResponse createPayment(
            CreatePaymentRequest request,
            UUID authenticatedUserId) {

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(authenticatedUserId)
                .amount(request.getAmount())
                .status(PaymentStatus.PENDING)
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        if (request.getAmount().compareTo(
                new BigDecimal("100000")) < 0) {

            savedPayment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(savedPayment);

            PaymentCompletedEvent event =
                    PaymentCompletedEvent.builder()
                            .paymentId(savedPayment.getId())
                            .orderId(savedPayment.getOrderId())
                            .userId(savedPayment.getUserId())
                            .amount(savedPayment.getAmount())
                            .build();

            paymentEventProducer.publishPaymentCompleted(event);

        } else {

            savedPayment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(savedPayment);

            PaymentFailedEvent event =
                    PaymentFailedEvent.builder()
                            .paymentId(savedPayment.getId())
                            .orderId(savedPayment.getOrderId())
                            .userId(savedPayment.getUserId())
                            .amount(savedPayment.getAmount())
                            .build();

            paymentEventProducer.publishPaymentFailed(event);
        }

        return mapToResponse(savedPayment);
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

        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
