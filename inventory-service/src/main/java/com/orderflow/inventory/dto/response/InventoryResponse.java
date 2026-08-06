package com.orderflow.inventory.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryResponse {

    private UUID id;

    private UUID productId;

    private Integer availableQuantity;

    private Integer reservedQuantity;

    private Instant createdAt;

    private Instant updatedAt;

}