package com.orderflow.inventory.exception;

public class InsufficientReservedStockException extends RuntimeException {

    public InsufficientReservedStockException(String message) {
        super(message);
    }
}