package com.orderflow.order.client;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
@Component
public class PaymentStatusClient {
    private final WebClient.Builder builder;
    private final String secret;
    public PaymentStatusClient(WebClient.Builder builder,@Value("${jwt.secret}") String secret) {this.builder=builder;this.secret=secret;}
    public Status get(UUID paymentId) {
        var now=Instant.now();
        String token=Jwts.builder().subject("order-service").audience().add("payment-service").and()
                .claim("purpose","payment-status").claim("paymentId",paymentId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(30)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        return builder.build().get().uri("http://PAYMENT-SERVICE/internal/v1/payments/{id}",paymentId)
                .header("Authorization","Bearer "+token).retrieve().bodyToMono(Status.class).block(Duration.ofSeconds(10));
    }
    public record Status(UUID id,UUID orderId,UUID userId,BigDecimal amount,String currency,String status) {}
}
