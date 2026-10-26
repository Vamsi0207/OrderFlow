package com.orderflow.order.client;

import com.orderflow.order.dto.inventory.request.InventoryAvailabilityRequest;
import com.orderflow.order.dto.inventory.request.InventoryReservationRequest;
import com.orderflow.order.dto.inventory.response.InventoryAvailabilityResponse;
import com.orderflow.order.dto.inventory.response.InventoryReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class InventoryClient {

    private final WebClient.Builder webClientBuilder;
    private final com.orderflow.order.checkout.ReservationTokens tokens;

    public InventoryAvailabilityResponse checkAvailability(
            InventoryAvailabilityRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://INVENTORY-SERVICE/api/v1/inventory/availability")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(InventoryAvailabilityResponse.class)
                .block(java.time.Duration.ofSeconds(10));
    }

    public InventoryReservationResponse reserveStock(
            InventoryReservationRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://INVENTORY-SERVICE/api/v1/inventory/reserve")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(InventoryReservationResponse.class)
                .block(java.time.Duration.ofSeconds(10));
    }
     
     public InventoryReservationResponse releaseStock(
        InventoryReservationRequest request) {

    return webClientBuilder.build()
            .post()
            .uri("http://INVENTORY-SERVICE/api/v1/inventory/release")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(InventoryReservationResponse.class)
            .block(java.time.Duration.ofSeconds(10));
}

public InventoryReservationResponse deductStock(
        InventoryReservationRequest request) {

    return webClientBuilder.build()
            .post()
            .uri("http://INVENTORY-SERVICE/api/v1/inventory/deduct")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(InventoryReservationResponse.class)
            .block(java.time.Duration.ofSeconds(10));
}

    public boolean hasScopedReservation(java.util.UUID orderId) {
        try {
            webClientBuilder.build().get().uri("http://INVENTORY-SERVICE/api/v1/inventory/reservations/{id}",orderId)
                    .header("Authorization",tokens.authorization(orderId,"read")).retrieve().toBodilessEntity().block(java.time.Duration.ofSeconds(10));
            return true;
        } catch(org.springframework.web.reactive.function.client.WebClientResponseException.NotFound absent) { return false; }
    }
    public void finishScoped(java.util.UUID orderId,boolean completed) {
        webClientBuilder.build().post().uri("http://INVENTORY-SERVICE/api/v1/inventory/reservations/{id}/"+(completed?"deduct":"release"),orderId)
                .header("Authorization",tokens.authorization(orderId,completed?"deduct":"release")).retrieve().toBodilessEntity().block(java.time.Duration.ofSeconds(10));
    }
}
