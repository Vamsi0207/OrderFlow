package com.orderflow.gateway.security;

import com.orderflow.gateway.service.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JwtGatewayFilter implements GlobalFilter {

    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain) {

        String path = exchange.getRequest()
                .getURI()
                .getPath();

        // Public authentication endpoints
        if (path.startsWith("/api/v1/auth/")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        // Authorization header missing
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse()
                    .setStatusCode(HttpStatus.UNAUTHORIZED);

            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        // JWT invalid or expired
        if (!jwtService.isTokenValid(token)) {
            exchange.getResponse()
                    .setStatusCode(HttpStatus.UNAUTHORIZED);

            return exchange.getResponse().setComplete();
        }

        try {
            Claims claims = jwtService.extractClaims(token);

            String userId = claims.get("userId", String.class);
            String role = claims.get("role", String.class);

            if (userId == null || role == null) {
                exchange.getResponse()
                        .setStatusCode(HttpStatus.UNAUTHORIZED);

                return exchange.getResponse().setComplete();
            }

            // Create a new request containing trusted user information
            ServerHttpRequest request = exchange.getRequest()
                    .mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Role");

                        headers.add("X-User-Id", userId);
                        headers.add("X-User-Role", role);
                    })
                    .build();

            // Create a new exchange using the modified request
            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(request)
                    .build();

            // Forward the modified request
            return chain.filter(modifiedExchange);

        } catch (Exception e) {

            exchange.getResponse()
                    .setStatusCode(HttpStatus.UNAUTHORIZED);

            return exchange.getResponse().setComplete();
        }
    }
}
