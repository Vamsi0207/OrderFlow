package com.orderflow.inventory.reservation;

import com.orderflow.inventory.entity.Inventory;
import com.orderflow.inventory.exception.*;
import com.orderflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StockReservationService {
    private final StockReservationRepository reservations;
    private final InventoryRepository inventory;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(UUID orderId, ReservationRequest request) {
        Objects.requireNonNull(orderId, "Durable reservation identity required");
        var items = request.normalizedItems();
        // The unique PK arbitrates concurrent identical requests, including absent-row races.
        // Header, lines and all stock changes commit together; failures roll everything back.
        int inserted = jdbc.update("""
                INSERT INTO stock_reservations (order_id, state, version, created_at, updated_at)
                VALUES (?, 'RESERVED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (order_id) DO NOTHING
                """, orderId);
        var reservation = reservations.findLocked(orderId).orElseThrow();
        if (inserted == 0) {
            if (!reservation.getItems().equals(items))
                throw new ReservationConflictException("Reservation identity already used with different items");
            // Terminal reservations are returned, never reopened or reserved again.
            return ReservationResponse.from(reservation);
        }
        var rows = lockProducts(items.keySet());
        items.forEach((id, quantity) -> {
            var row = rows.get(id);
            if (row.getAvailableQuantity() < quantity)
                throw new InsufficientStockException("Insufficient stock for product: " + id);
            row.setAvailableQuantity(row.getAvailableQuantity() - quantity);
            row.setReservedQuantity(Math.addExact(row.getReservedQuantity(), quantity));
        });
        reservation.getItems().putAll(items);
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID orderId) {
        return ReservationResponse.from(reservations.findById(orderId).orElseThrow(ReservationNotFoundException::new));
    }

    @Transactional
    public ReservationResponse release(UUID orderId) { return finish(orderId, StockReservation.State.RELEASED); }

    @Transactional
    public ReservationResponse deduct(UUID orderId) { return finish(orderId, StockReservation.State.DEDUCTED); }

    private ReservationResponse finish(UUID orderId, StockReservation.State target) {
        var reservation = reservations.findLocked(orderId).orElseThrow(ReservationNotFoundException::new);
        if (reservation.getState() == target) return ReservationResponse.from(reservation);
        if (reservation.getState() != StockReservation.State.RESERVED)
            throw new ReservationConflictException("Reservation is already " + reservation.getState());
        var rows = lockProducts(reservation.getItems().keySet());
        reservation.getItems().forEach((id, quantity) -> {
            var row = rows.get(id);
            if (row.getReservedQuantity() < quantity)
                throw new IllegalStateException("Reserved stock invariant violated: " + id);
            row.setReservedQuantity(row.getReservedQuantity() - quantity);
            if (target == StockReservation.State.RELEASED)
                row.setAvailableQuantity(Math.addExact(row.getAvailableQuantity(), quantity));
        });
        reservation.setState(target);
        return ReservationResponse.from(reservation);
    }

    private Map<UUID, Inventory> lockProducts(Collection<UUID> ids) {
        var rows = new HashMap<UUID, Inventory>();
        // Every writer acquires product locks in this order to avoid multi-item deadlocks.
        for (UUID id : new TreeSet<>(ids))
            rows.put(id, inventory.findLockedByProductId(id)
                    .orElseThrow(() -> new InventoryNotFoundException("Inventory not found for product: " + id)));
        return rows;
    }
}
