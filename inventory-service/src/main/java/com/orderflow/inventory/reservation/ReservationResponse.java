package com.orderflow.inventory.reservation;

import java.util.*;

public record ReservationResponse(UUID orderId, StockReservation.State state, Map<UUID, Integer> items) {
    public static ReservationResponse from(StockReservation reservation) {
        return new ReservationResponse(reservation.getOrderId(), reservation.getState(), Map.copyOf(reservation.getItems()));
    }
}
