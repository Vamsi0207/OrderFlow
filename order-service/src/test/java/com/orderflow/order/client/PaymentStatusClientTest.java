package com.orderflow.order.client;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class PaymentStatusClientTest {
    @Test void emitsShortLivedPaymentScopedServiceJwtInsteadOfUntrustedHeaders() {
        String key="phase2-test-only-jwt-key-at-least-32-characters";UUID id=UUID.randomUUID();
        var client=new PaymentStatusClient(WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/internal/v1/payments/"+id);
            String token=request.headers().getFirst("Authorization").substring(7);
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(token).getPayload();
            assertThat(claims.getSubject()).isEqualTo("order-service");assertThat(claims.getAudience()).contains("payment-service");
            assertThat(claims.get("purpose",String.class)).isEqualTo("payment-status");assertThat(claims.get("paymentId",String.class)).isEqualTo(id.toString());
            assertThat(claims.getExpiration().toInstant()).isBefore(Instant.now().plusSeconds(31));assertThat(claims.get("userId")).isNull();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
                    .body("{\"id\":\""+id+"\",\"status\":\"COMPLETED\"}").build());
        }),key);
        assertThat(client.get(id).status()).isEqualTo("COMPLETED");
    }
}
