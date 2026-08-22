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

    public InventoryAvailabilityResponse checkAvailability(
            InventoryAvailabilityRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://INVENTORY-SERVICE/api/v1/inventory/availability")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(InventoryAvailabilityResponse.class)
                .block();
    }

    public InventoryReservationResponse reserveStock(
            InventoryReservationRequest request) {

        return webClientBuilder.build()
                .post()
                .uri("http://INVENTORY-SERVICE/api/v1/inventory/reserve")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(InventoryReservationResponse.class)
                .block();
    }
     
     public InventoryReservationResponse releaseStock(
        InventoryReservationRequest request) {

    return webClientBuilder.build()
            .post()
            .uri("http://INVENTORY-SERVICE/api/v1/inventory/release")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(InventoryReservationResponse.class)
            .block();
}

public InventoryReservationResponse deductStock(
        InventoryReservationRequest request) {

    return webClientBuilder.build()
            .post()
            .uri("http://INVENTORY-SERVICE/api/v1/inventory/deduct")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(InventoryReservationResponse.class)
            .block();
}
}
