package com.orderflow.order.dto.inventory.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryAvailabilityResponse {

    private boolean available;

    private Integer availableQuantity;

}