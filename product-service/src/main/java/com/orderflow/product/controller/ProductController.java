package com.orderflow.product.controller;

import com.orderflow.product.dto.request.ProductRequest;
import com.orderflow.product.dto.response.ProductInfoResponse;
import com.orderflow.product.dto.response.ProductResponse;
import com.orderflow.product.service.ProductService;
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
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(
        name = "Product Management",
        description = "APIs for creating, retrieving, updating, and deleting products"
)
public class ProductController {

    private final ProductService productService;

    @Operation(
            summary = "Create a product",
            description = "Creates a new product in the catalog"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Product created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid product request"
            )
    })
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody ProductRequest request) {

        ProductResponse response = productService.createProduct(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @Operation(
            summary = "Get all products",
            description = "Retrieves all products in the catalog"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Products retrieved successfully"
    )
    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {

        return ResponseEntity.ok(
                productService.getAllProducts()
        );
    }

    @Operation(
            summary = "Get product by ID",
            description = "Retrieves a product using its ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Product found"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Product not found"
            )
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                productService.getProductById(id)
        );
    }

    @Operation(
            summary = "Update a product",
            description = "Updates an existing product by ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Product updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid product request"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Product not found"
            )
    })
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody ProductRequest request) {

        return ResponseEntity.ok(
                productService.updateProduct(id, request)
        );
    }

    @Operation(
            summary = "Delete a product",
            description = "Deletes a product by ID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Product deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Product not found"
            )
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable UUID id) {

        productService.deleteProduct(id);

        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Get product info",
            description = "Retrieves lightweight product details for order processing"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Product info retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Product not found"
            )
    })
    @GetMapping("/{id}/info")
    public ResponseEntity<ProductInfoResponse> getProductInfo(
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                productService.getProductInfo(id)
        );
    }
}
