package com.orderflow.order.checkout;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
@Component @RequiredArgsConstructor
public class CheckoutPaymentClient {
    private final WebClient.Builder builder;
    public Result initialize(UUID orderId,BuyActor actor) {
        return builder.build().post().uri("http://PAYMENT-SERVICE/api/v1/payments")
                .header("Authorization",actor.authorization()).bodyValue(Map.of("orderId",orderId))
                .exchangeToMono(response -> {
                    int status=response.statusCode().value();
                    if(status==201 || status==202 || status==409)return response.bodyToMono(Payment.class)
                            .defaultIfEmpty(new Payment(null,null,null,null,null,null,null,null))
                            .map(payment -> new Result(status,payment));
                    return response.createException().flatMap(Mono::error);
                }).block(Duration.ofSeconds(15));
    }
    public Payment lookup(UUID orderId,BuyActor actor) {
        return builder.build().get().uri("http://PAYMENT-SERVICE/api/v1/payments/order/{id}",orderId)
                .header("Authorization",actor.authorization()).retrieve().bodyToMono(Payment.class).block(Duration.ofSeconds(10));
    }
    public record Payment(UUID id,UUID orderId,UUID userId,BigDecimal amount,String currency,String status,String initializationState,String clientSecret) {}
    public record Result(int httpStatus,Payment payment) {}
}
