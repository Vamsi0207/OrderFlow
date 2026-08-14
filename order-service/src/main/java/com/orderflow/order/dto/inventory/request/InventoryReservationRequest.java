package com.orderflow.order.dto.inventory.request;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryReservationRequest {

    private UUID productId;

    private Integer quantity;

}