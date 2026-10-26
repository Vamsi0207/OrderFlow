package com.orderflow.inventory.reservation;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException() { super("Reservation not found; reconcile using the same identity, do not release anonymous stock"); }
}
