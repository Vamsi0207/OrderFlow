package com.orderflow.order.checkout;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
@Component
public class ReservationTokens {
    private final String secret;
    public ReservationTokens(@Value("${jwt.secret}") String secret) {this.secret=secret;}
    public String authorization(UUID id,String action) {
        return "Bearer "+Jwts.builder().subject("order-service").audience().add("inventory-service").and()
                .claim("purpose","inventory-reservation").claim("reservationId",id.toString()).claim("action",action)
                .issuedAt(Date.from(Instant.now())).expiration(Date.from(Instant.now().plusSeconds(30)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
