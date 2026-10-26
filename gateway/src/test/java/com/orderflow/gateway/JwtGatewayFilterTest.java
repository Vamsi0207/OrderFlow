package com.orderflow.gateway;
import com.orderflow.gateway.config.JwtProperties;
import com.orderflow.gateway.security.JwtGatewayFilter;
import com.orderflow.gateway.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class JwtGatewayFilterTest {
    static final String KEY="phase2-test-only-jwt-key-at-least-32-characters";
    JwtGatewayFilter filter() {var p=new JwtProperties();p.setSecret(KEY);return new JwtGatewayFilter(new JwtService(p));}
    @Test void onlyExactPostWebhookBypassesJwtAndSpoofedIdentityIsRemoved() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/payments/webhook").header("X-User-Id","spoofed").header("X-User-Role","ADMIN"));
        var forwarded=new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        filter().filter(exchange,e -> {forwarded.set(e);return reactor.core.publisher.Mono.empty();}).block();
        assertThat(forwarded.get()).isNotNull();assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isNull();
        for(String path:List.of("/api/v1/payments","/api/v1/payments/webhook/","/api/v1/payments/webhook/child")) {
            var denied=MockServerWebExchange.from(MockServerHttpRequest.post(path));
            filter().filter(denied,e -> {throw new AssertionError("Unexpected exemption");}).block();assertThat(denied.getResponse().getStatusCode().value()).isEqualTo(401);
        }
        var denied=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/payments/webhook"));
        filter().filter(denied,e -> {throw new AssertionError();}).block();assertThat(denied.getResponse().getStatusCode().value()).isEqualTo(401);
    }
    @Test void normalJwtValidationAndTrustedHeadersArePreserved() {
        UUID user=UUID.randomUUID();
        String token=Jwts.builder().claim("userId",user.toString()).claim("role","USER").expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/payments").header("Authorization","Bearer "+token).header("X-User-Id","attacker"));
        filter().filter(exchange,e -> {assertThat(e.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(user.toString());
            assertThat(e.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer "+token);return reactor.core.publisher.Mono.empty();}).block();
        var invalid=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products").header("Authorization","Bearer bad"));
        filter().filter(invalid,e -> {throw new AssertionError();}).block();assertThat(invalid.getResponse().getStatusCode().value()).isEqualTo(401);
    }
    @Test void existingAuthExemptionRemains() {
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        filter().filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")),e -> {called.set(true);return reactor.core.publisher.Mono.empty();}).block();
        assertThat(called).isTrue();
    }
}
