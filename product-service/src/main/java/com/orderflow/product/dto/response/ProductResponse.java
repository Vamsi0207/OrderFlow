package com.orderflow.product.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private UUID id;

    private String name;

    private String description;

    private BigDecimal price;

    private Integer stock;

    private String category;

    private String imageUrl;

    private Instant createdAt;
}