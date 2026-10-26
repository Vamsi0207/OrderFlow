package com.orderflow.payment.controller;
import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.enums.InitializationState;
import com.orderflow.payment.security.PaymentActor;
import com.orderflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/payments") @RequiredArgsConstructor
public class PaymentController {
    private final PaymentService service;
    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request,
            @RequestAttribute("paymentActor") PaymentActor actor) {
        var result=service.createPayment(request,actor);
        var state=result.getInitializationState();
        return ResponseEntity.status(state==InitializationState.RECONCILIATION_REQUIRED?409:
                state==InitializationState.READY?201:202).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/order/{orderId}") public PaymentResponse byOrder(@PathVariable UUID orderId,@RequestAttribute("paymentActor") PaymentActor actor) {
        return service.getPaymentByOrder(orderId,actor);
    }
    @GetMapping("/{id}") public PaymentResponse get(@PathVariable UUID id,@RequestAttribute("paymentActor") PaymentActor actor) {
        return service.getPaymentById(id,actor);
    }
    @GetMapping public List<PaymentResponse> all(@RequestAttribute("paymentActor") PaymentActor actor) { return service.getAllPayments(actor); }
    @GetMapping("/user/{userId}") public List<PaymentResponse> byUser(@PathVariable UUID userId,@RequestAttribute("paymentActor") PaymentActor actor) {
        return service.getPaymentsByUser(userId,actor);
    }
}
