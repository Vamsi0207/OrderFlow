package com.orderflow.inventory.reservation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/reservations")
@RequiredArgsConstructor
public class StockReservationController {
    private final StockReservationService service;

    @PutMapping("/{orderId}")
    public ReservationResponse reserve(@PathVariable UUID orderId, @Valid @RequestBody ReservationRequest request) {
        return service.reserve(orderId, request);
    }
    @GetMapping("/{orderId}")
    public ReservationResponse get(@PathVariable UUID orderId) { return service.get(orderId); }
    @PostMapping("/{orderId}/release")
    public ReservationResponse release(@PathVariable UUID orderId) { return service.release(orderId); }
    @PostMapping("/{orderId}/deduct")
    public ReservationResponse deduct(@PathVariable UUID orderId) { return service.deduct(orderId); }
}
