package com.orderflow.inventory.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryReservationResponse {

    private boolean success;

    private Integer availableQuantity;

    private Integer reservedQuantity;

    private String message;
}