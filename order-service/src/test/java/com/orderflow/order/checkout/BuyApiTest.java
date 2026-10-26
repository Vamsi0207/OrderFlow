package com.orderflow.order.checkout;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class BuyApiTest {
    final String key="phase3-test-only-signing-key-at-least-32-characters";
    @Test void requiresVerifiedJwtIgnoresSpoofedIdentityAndUsesNoStore() throws Exception {
        var service=mock(BuyNowService.class);UUID user=UUID.randomUUID();
        when(service.buy(any(),any(),any())).thenAnswer(call -> {
            BuyActor actor=call.getArgument(0);assertThat(actor.userId()).isEqualTo(user);
            return new BuyNowService.Result(201,new BuyNowResponse(UUID.randomUUID(),"COMPLETED",null,UUID.randomUUID(),"PENDING",java.math.BigDecimal.TEN,"INR",List.of(),new BuyNowResponse.Payment(UUID.randomUUID(),"PENDING","READY","test-secret")));
        });
        var mvc=MockMvcBuilders.standaloneSetup(new BuyNowController(service)).setControllerAdvice(new BuyErrorHandler()).addFilters(new BuyAuthenticationFilter(key)).build();
        String body="{\"items\":[{\"productId\":\""+UUID.randomUUID()+"\",\"quantity\":1}]}";
        mvc.perform(post("/api/v1/buy").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buy").header("Authorization","Bearer invalid").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        String token=Jwts.builder().claim("userId",user.toString()).claim("role","USER").expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(post("/api/v1/buy").header("Authorization","Bearer "+token).header("X-User-Id",UUID.randomUUID()).header("Idempotency-Key","key")
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/buy").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/buy").header("Authorization","Bearer "+token).header("Idempotency-Key","bad").contentType("application/json").content("{\"items\":[{\"productId\":\""+UUID.randomUUID()+"\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
        doThrow(new CheckoutConflictException()).when(service).buy(any(),eq("conflict"),any());
        mvc.perform(post("/api/v1/buy").header("Authorization","Bearer "+token).header("Idempotency-Key","conflict").contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
