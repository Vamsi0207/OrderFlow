package com.orderflow.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.enums.OrderStatus;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3, columnDefinition = "varchar(3) default 'INR'")
    @Builder.Default
    private String currency = "INR";

    @Column(unique = true)
    private UUID checkoutAttemptId;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean scopedReservation;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean inventoryReserved;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();
}