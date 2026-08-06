package com.orderflow.inventory.controller;

import com.orderflow.inventory.dto.request.InventoryAvailabilityRequest;
import com.orderflow.inventory.dto.request.InventoryRequest;
import com.orderflow.inventory.dto.request.ReserveStockRequest;
import com.orderflow.inventory.dto.response.InventoryAvailabilityResponse;
import com.orderflow.inventory.dto.response.InventoryReservationResponse;
import com.orderflow.inventory.dto.response.InventoryResponse;
import com.orderflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<InventoryResponse> createInventory(
            @Valid @RequestBody InventoryRequest request) {

        InventoryResponse response =
                inventoryService.createInventory(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<InventoryResponse> getInventoryByProductId(
            @PathVariable UUID productId) {

        return ResponseEntity.ok(
                inventoryService.getInventoryByProductId(productId)
        );
    }

    @GetMapping
    public ResponseEntity<List<InventoryResponse>> getAllInventories() {

        return ResponseEntity.ok(
                inventoryService.getAllInventories()
        );
    }

    @PutMapping("/{productId}")
    public ResponseEntity<InventoryResponse> updateInventory(
            @PathVariable UUID productId,
            @Valid @RequestBody InventoryRequest request) {

        return ResponseEntity.ok(
                inventoryService.updateInventory(productId, request)
        );
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteInventory(
            @PathVariable UUID productId) {

        inventoryService.deleteInventory(productId);

        return ResponseEntity.noContent().build();
    }
    @PostMapping("/availability")
    public ResponseEntity<InventoryAvailabilityResponse> checkAvailability(
        @Valid @RequestBody InventoryAvailabilityRequest request) {

          return ResponseEntity.ok(
            inventoryService.checkAvailability(request)
        );
    }

        @PostMapping("/reserve")
        public ResponseEntity<InventoryReservationResponse> reserveStock(
        @Valid @RequestBody ReserveStockRequest request) {

        return ResponseEntity.ok(
            inventoryService.reserveStock(request)
     );
    }

    @PostMapping("/release")
    public ResponseEntity<InventoryReservationResponse> releaseStock(
        @Valid @RequestBody ReserveStockRequest request) {

        return ResponseEntity.ok(
            inventoryService.releaseStock(request)
        );
    }

    @PostMapping("/deduct")
    public ResponseEntity<InventoryReservationResponse> deductStock(
        @Valid @RequestBody ReserveStockRequest request) {

    return ResponseEntity.ok(
            inventoryService.deductStock(request)
    );
    }
}