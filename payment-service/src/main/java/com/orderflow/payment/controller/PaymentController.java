package com.orderflow.payment.controller;

import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader("X-User-Id") UUID userId) {

        return paymentService.createPayment(request, userId);
    }

    @GetMapping("/{id}")
    public PaymentResponse getPaymentById(
            @PathVariable UUID id) {

        return paymentService.getPaymentById(id);
    }

    @GetMapping
    public List<PaymentResponse> getAllPayments() {

        return paymentService.getAllPayments();
    }

    @GetMapping("/user/{userId}")
    public List<PaymentResponse> getPaymentsByUser(
            @PathVariable UUID userId) {

        return paymentService.getPaymentsByUser(userId);
    }
}
