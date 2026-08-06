package com.orderflow.product.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductInfoResponse {

    private UUID id;
    private String name;
    private BigDecimal price;
    private Integer stock;
}