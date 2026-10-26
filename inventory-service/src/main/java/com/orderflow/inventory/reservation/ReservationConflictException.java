package com.orderflow.inventory.reservation;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) { super(message); }
}
