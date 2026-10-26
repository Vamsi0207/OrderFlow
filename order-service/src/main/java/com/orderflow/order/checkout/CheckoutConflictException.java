package com.orderflow.order.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CheckoutConflictException extends RuntimeException {
    public CheckoutConflictException() { super("Idempotency key was already used with a different checkout payload"); }
}
