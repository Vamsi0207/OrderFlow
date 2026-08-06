package com.orderflow.order.dto.response;

import com.orderflow.order.enums.OrderStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {

    private UUID id;

    private UUID userId;

    private OrderStatus status;

    private BigDecimal totalAmount;

    private List<OrderItemResponse> items;

    private Instant createdAt;
}