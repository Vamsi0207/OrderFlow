package com.orderflow.order.checkout;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestControllerAdvice(assignableTypes=BuyNowController.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class BuyErrorHandler {
    @ExceptionHandler(CheckoutConflictException.class)
    public ResponseEntity<Map<String,String>> conflict() {return ResponseEntity.status(409).body(Map.of("code","IDEMPOTENCY_CONFLICT"));}
    @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    public ResponseEntity<Map<String,String>> invalid() {return ResponseEntity.badRequest().body(Map.of("code","INVALID_CHECKOUT"));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unavailable() {return ResponseEntity.status(503).body(Map.of("code","CHECKOUT_UNAVAILABLE"));}
}
