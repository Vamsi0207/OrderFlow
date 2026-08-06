package com.orderflow.inventory.service;

import com.orderflow.inventory.dto.request.InventoryAvailabilityRequest;
import com.orderflow.inventory.dto.request.InventoryRequest;
import com.orderflow.inventory.dto.request.ReserveStockRequest;
import com.orderflow.inventory.dto.response.InventoryAvailabilityResponse;
import com.orderflow.inventory.dto.response.InventoryReservationResponse;
import com.orderflow.inventory.dto.response.InventoryResponse;
import com.orderflow.inventory.entity.Inventory;
import com.orderflow.inventory.exception.InsufficientReservedStockException;
import com.orderflow.inventory.exception.InsufficientStockException;
import com.orderflow.inventory.exception.InventoryAlreadyExistsException;
import com.orderflow.inventory.exception.InventoryNotFoundException;
import com.orderflow.inventory.repository.InventoryRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryResponse createInventory(InventoryRequest request) {

        if (inventoryRepository.existsByProductId(request.getProductId())) {
            throw new InventoryAlreadyExistsException(
        "Inventory already exists for product: "
                + request.getProductId()
);
        }

        Inventory inventory = Inventory.builder()
                .productId(request.getProductId())
                .availableQuantity(request.getAvailableQuantity())
                .build();

        inventory = inventoryRepository.save(inventory);

        return mapToResponse(inventory);
    }

    public InventoryResponse getInventoryByProductId(UUID productId) {

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory not found for product: " + productId
                ));

        return mapToResponse(inventory);
    }

    public List<InventoryResponse> getAllInventories() {

        return inventoryRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    public InventoryResponse updateInventory(
            UUID productId,
            InventoryRequest request) {

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory not found for product: " + productId
                ));

        inventory.setAvailableQuantity(request.getAvailableQuantity());

        inventory = inventoryRepository.save(inventory);

        return mapToResponse(inventory);
    }

    public void deleteInventory(UUID productId) {

        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory not found for product: " + productId
                ));

        inventoryRepository.delete(inventory);
    }

    private InventoryResponse mapToResponse(Inventory inventory) {

        return InventoryResponse.builder()
                .id(inventory.getId())
                .productId(inventory.getProductId())
                .availableQuantity(inventory.getAvailableQuantity())
                .reservedQuantity(inventory.getReservedQuantity())
                .createdAt(inventory.getCreatedAt())
                .updatedAt(inventory.getUpdatedAt())
                .build();
    }

    public InventoryAvailabilityResponse checkAvailability(
        InventoryAvailabilityRequest request) {

        Inventory inventory = inventoryRepository.findByProductId(
                    request.getProductId())
            .orElseThrow(() ->
                    new InventoryNotFoundException(
                            "Inventory not found for product: "
                                    + request.getProductId()));

        boolean available =
            inventory.getAvailableQuantity() >= request.getQuantity();

        return InventoryAvailabilityResponse.builder()
            .available(available)
            .availableQuantity(inventory.getAvailableQuantity())
            .build();
    }

    @Transactional
    public InventoryReservationResponse reserveStock(
        ReserveStockRequest request) {

        Inventory inventory = inventoryRepository.findByProductId(
                    request.getProductId())
            .orElseThrow(() -> new InventoryNotFoundException(
                    "Inventory not found for product: "
                            + request.getProductId()));

        if (inventory.getAvailableQuantity() < request.getQuantity()) {
        throw new InsufficientStockException(
                "Insufficient stock for product: "
                        + request.getProductId());
     }

         inventory.setAvailableQuantity(
            inventory.getAvailableQuantity() - request.getQuantity());

          inventory.setReservedQuantity(
            inventory.getReservedQuantity() + request.getQuantity());

          inventoryRepository.save(inventory);

        return InventoryReservationResponse.builder()
            .success(true)
            .availableQuantity(inventory.getAvailableQuantity())
            .reservedQuantity(inventory.getReservedQuantity())
            .message("Stock reserved successfully.")
            .build();
    }

    @Transactional
    public InventoryReservationResponse releaseStock(
        ReserveStockRequest request) {

        Inventory inventory = inventoryRepository.findByProductId(
                    request.getProductId())
            .orElseThrow(() -> new InventoryNotFoundException(
                    "Inventory not found for product: "
                            + request.getProductId()));

         if (inventory.getReservedQuantity() < request.getQuantity()) {
        throw new InsufficientReservedStockException(
                "Cannot release more stock than reserved for product: "
                        + request.getProductId());
         }

        inventory.setReservedQuantity(
            inventory.getReservedQuantity() - request.getQuantity());

      inventory.setAvailableQuantity(
         inventory.getAvailableQuantity() + request.getQuantity());

     inventoryRepository.save(inventory);

        return InventoryReservationResponse.builder()
            .success(true)
            .availableQuantity(inventory.getAvailableQuantity())
            .reservedQuantity(inventory.getReservedQuantity())
            .message("Stock released successfully.")
            .build();
    }

    @Transactional
    public InventoryReservationResponse deductStock(
        ReserveStockRequest request) {

        Inventory inventory = inventoryRepository.findByProductId(
                    request.getProductId())
            .orElseThrow(() -> new InventoryNotFoundException(
                    "Inventory not found for product: "
                            + request.getProductId()));

        if (inventory.getReservedQuantity() < request.getQuantity()) {
        throw new InsufficientReservedStockException(
                "Cannot deduct more stock than reserved for product: "
                        + request.getProductId());
     }

        inventory.setReservedQuantity(
            inventory.getReservedQuantity() - request.getQuantity());

         inventoryRepository.save(inventory);

         return InventoryReservationResponse.builder()
            .success(true)
            .availableQuantity(inventory.getAvailableQuantity())
            .reservedQuantity(inventory.getReservedQuantity())
            .message("Stock deducted successfully.")
            .build();
    }
}