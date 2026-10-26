package com.orderflow.inventory.reservation;

import com.orderflow.inventory.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.*;

@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
public class StockReservation extends BaseEntity {
    /** An existing order ID or durable checkout-attempt ID; stable across retries. */
    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;
    @ElementCollection
    @CollectionTable(name = "stock_reservation_items", joinColumns = @JoinColumn(name = "order_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_reservation_product", columnNames = {"order_id", "product_id"}),
            indexes = @Index(name = "ix_reservation_product", columnList = "product_id"))
    @MapKeyColumn(name = "product_id", nullable = false)
    @Column(name = "quantity", nullable = false)
    private Map<UUID, Integer> items = new HashMap<>();
    @Version
    private long version;

    public enum State { RESERVED, RELEASED, DEDUCTED }
}
