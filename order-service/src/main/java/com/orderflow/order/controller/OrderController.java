package com.orderflow.order.controller;

import com.orderflow.order.dto.request.CreateOrderRequest;
import com.orderflow.order.dto.response.OrderResponse;
import com.orderflow.order.security.OrderAuthorization;
import com.orderflow.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderAuthorization orderAuthorization;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("X-User-Id") UUID userId) {

        OrderResponse response =
                orderService.createOrder(request, userId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders(
            @RequestHeader("X-User-Role") String role) {

        orderAuthorization.requireAdmin(role);

        return ResponseEntity.ok(
                orderService.getAllOrders()
        );
    }

    @GetMapping("/my-orders")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @RequestHeader("X-User-Id") UUID userId) {

        return ResponseEntity.ok(
                orderService.getOrdersByUser(userId)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") String role) {

        return ResponseEntity.ok(
                orderService.getOrderById(id, userId, role)
        );
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getOrdersByUser(
            @PathVariable UUID userId,
            @RequestHeader("X-User-Role") String role) {

        orderAuthorization.requireAdmin(role);

        return ResponseEntity.ok(
                orderService.getOrdersByUser(userId)
        );
    }
}
