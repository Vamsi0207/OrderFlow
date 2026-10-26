package com.orderflow.order.checkout;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.*;
@Component @RequiredArgsConstructor
public class CheckoutInventoryClient {
    private final WebClient.Builder builder;
    private final ReservationTokens tokens;
    public Reservation reserve(UUID orderId,CheckoutRequest request) {
        return builder.build().put().uri("http://INVENTORY-SERVICE/api/v1/inventory/reservations/{id}",orderId)
                .header("Authorization",tokens.authorization(orderId,"reserve")).bodyValue(request).retrieve().bodyToMono(Reservation.class).block(Duration.ofSeconds(10));
    }
    public record Reservation(UUID orderId,String state,Map<UUID,Integer> items) {}
}
