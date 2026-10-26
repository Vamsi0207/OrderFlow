package com.orderflow.order.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ProductClientTest {
    @Test void orderLookupUsesUncachedCheckoutEndpoint() {
        UUID id=UUID.randomUUID();var path=new AtomicReference<String>();
        var client=new ProductClient(WebClient.builder().exchangeFunction(request -> {
            path.set(request.url().getPath());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
                    .body("{\"id\":\""+id+"\",\"price\":12.50,\"stock\":5}").build());
        }));
        assertThat(client.getProduct(id).getPrice()).isEqualByComparingTo("12.50");
        assertThat(path.get()).isEqualTo("/api/v1/products/"+id+"/checkout-price");
    }
}
