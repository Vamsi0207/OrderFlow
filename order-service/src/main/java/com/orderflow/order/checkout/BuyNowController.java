package com.orderflow.order.checkout;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class BuyNowController {
    private final BuyNowService service;
    @PostMapping("/api/v1/buy")
    public ResponseEntity<BuyNowResponse> buy(@RequestAttribute("buyActor") BuyActor actor,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CheckoutRequest request) {
        var result=service.buy(actor,key,request);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.body());
    }
}
