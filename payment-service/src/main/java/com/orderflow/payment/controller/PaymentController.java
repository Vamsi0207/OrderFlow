package com.orderflow.payment.controller;

import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(
        name = "Payment Management",
        description = "APIs for creating and retrieving payments"
)
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(
            summary = "Create a payment",
            description = "Creates and processes a payment for an order"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Payment created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid payment request"
            )
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader("X-User-Id") UUID userId) {

        return paymentService.createPayment(request, userId);
    }

    @Operation(
            summary = "Get payment by ID",
            description = "Retrieves payment details using the payment ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Payment found"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Payment not found"
            )
    })
    @GetMapping("/{id}")
    public PaymentResponse getPaymentById(
            @PathVariable UUID id) {

        return paymentService.getPaymentById(id);
    }

    @Operation(
            summary = "Get all payments",
            description = "Retrieves all payments"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Payments retrieved successfully"
    )
    @GetMapping
    public List<PaymentResponse> getAllPayments() {

        return paymentService.getAllPayments();
    }

    @Operation(
            summary = "Get payments by user",
            description = "Retrieves all payments belonging to a specific user"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Payments retrieved successfully"
    )
    @GetMapping("/user/{userId}")
    public List<PaymentResponse> getPaymentsByUser(
            @PathVariable UUID userId) {

        return paymentService.getPaymentsByUser(userId);
    }
}
