package com.orderflow.order.client;

import com.orderflow.order.dto.response.ProductInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProductClient {

    private final WebClient.Builder webClientBuilder;

    private static final String PRODUCT_SERVICE = "http://PRODUCT-SERVICE";

public ProductInfoResponse getProduct(UUID productId) {
    return webClientBuilder.build()
            .get()
            .uri(PRODUCT_SERVICE + "/api/v1/products/{id}/info", productId)
            .retrieve()
            .bodyToMono(ProductInfoResponse.class)
            .block();
}
}