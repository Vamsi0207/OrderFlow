package com.orderflow.product.dto.response;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductInfoResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID id;
    private String name;
    private BigDecimal price;
    private Integer stock;
}
