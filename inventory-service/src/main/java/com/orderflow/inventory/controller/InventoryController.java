package com.orderflow.inventory.controller;

import com.orderflow.inventory.dto.request.InventoryAvailabilityRequest;
import com.orderflow.inventory.dto.request.InventoryRequest;
import com.orderflow.inventory.dto.request.ReserveStockRequest;
import com.orderflow.inventory.dto.response.InventoryAvailabilityResponse;
import com.orderflow.inventory.dto.response.InventoryReservationResponse;
import com.orderflow.inventory.dto.response.InventoryResponse;
import com.orderflow.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Inventory Management",
        description = "APIs for managing inventory, stock availability, reservations, and deductions"
)
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(
            summary = "Create inventory",
            description = "Creates inventory record for a product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Inventory created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid inventory request"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Inventory already exists for product"
            )
    })
    @PostMapping
    public ResponseEntity<InventoryResponse> createInventory(
            @Valid @RequestBody InventoryRequest request) {

        InventoryResponse response =
                inventoryService.createInventory(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @Operation(
            summary = "Get inventory by product ID",
            description = "Retrieves inventory details for a specific product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Inventory found"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @GetMapping("/{productId}")
    public ResponseEntity<InventoryResponse> getInventoryByProductId(
            @PathVariable UUID productId) {

        return ResponseEntity.ok(
                inventoryService.getInventoryByProductId(productId)
        );
    }

    @Operation(
            summary = "Get all inventories",
            description = "Retrieves all inventory records"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Inventories retrieved successfully"
    )
    @GetMapping
    public ResponseEntity<List<InventoryResponse>> getAllInventories() {

        return ResponseEntity.ok(
                inventoryService.getAllInventories()
        );
    }

    @Operation(
            summary = "Update inventory",
            description = "Updates inventory quantity for a product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Inventory updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid inventory request"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @PutMapping("/{productId}")
    public ResponseEntity<InventoryResponse> updateInventory(
            @PathVariable UUID productId,
            @Valid @RequestBody InventoryRequest request) {

        return ResponseEntity.ok(
                inventoryService.updateInventory(productId, request)
        );
    }

    @Operation(
            summary = "Delete inventory",
            description = "Deletes inventory record for a product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Inventory deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteInventory(
            @PathVariable UUID productId) {

        inventoryService.deleteInventory(productId);

        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Check stock availability",
            description = "Checks whether requested quantity is available for a product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Availability check completed"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid availability request"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @PostMapping("/availability")
    public ResponseEntity<InventoryAvailabilityResponse> checkAvailability(
        @Valid @RequestBody InventoryAvailabilityRequest request) {

          return ResponseEntity.ok(
            inventoryService.checkAvailability(request)
        );
    }

    @Operation(
            summary = "Reserve stock",
            description = "Reserves stock for an order by moving quantity from available to reserved"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Stock reserved successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request or insufficient stock"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
        @PostMapping("/reserve")
        public ResponseEntity<InventoryReservationResponse> reserveStock(
        @Valid @RequestBody ReserveStockRequest request) {

        return ResponseEntity.ok(
            inventoryService.reserveStock(request)
     );
    }

    @Operation(
            summary = "Release stock",
            description = "Releases previously reserved stock back to available quantity"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Stock released successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request or insufficient reserved stock"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @PostMapping("/release")
    public ResponseEntity<InventoryReservationResponse> releaseStock(
        @Valid @RequestBody ReserveStockRequest request) {

        return ResponseEntity.ok(
            inventoryService.releaseStock(request)
        );
    }

    @Operation(
            summary = "Deduct stock",
            description = "Deducts reserved stock after order fulfillment"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Stock deducted successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request or insufficient reserved stock"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Inventory not found"
            )
    })
    @PostMapping("/deduct")
    public ResponseEntity<InventoryReservationResponse> deductStock(
        @Valid @RequestBody ReserveStockRequest request) {

    return ResponseEntity.ok(
            inventoryService.deductStock(request)
    );
    }
}
