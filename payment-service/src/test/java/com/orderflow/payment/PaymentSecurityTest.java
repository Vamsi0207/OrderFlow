package com.orderflow.payment;
import com.orderflow.payment.controller.*;
import com.orderflow.payment.security.*;
import com.orderflow.payment.service.*;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.*;
import com.orderflow.payment.config.StripeProperties;
import com.orderflow.payment.exception.GlobalExceptionHandler;
import com.orderflow.payment.dto.response.PaymentResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PaymentSecurityTest {
    static final String KEY="phase2-test-only-jwt-key-at-least-32-characters";
    static final String WEBHOOK="whsec_phase2_test_only";
    PaymentService service=mock(PaymentService.class);
    PaymentWebhookService webhooks=mock(PaymentWebhookService.class);
    PaymentRepository repository=mock(PaymentRepository.class);
    MockMvc mvc() {
        var properties=new StripeProperties();properties.setWebhookSecret(WEBHOOK);
        return MockMvcBuilders.standaloneSetup(new PaymentController(service),new PaymentWebhookController(webhooks,properties),new InternalPaymentController(repository))
                .addFilters(new PaymentAuthenticationFilter(KEY)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    String user(UUID id,Instant expiry) {
        return "Bearer "+Jwts.builder().subject("user@example.test").claim("userId",id.toString()).claim("role","USER")
                .expiration(Date.from(expiry)).signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    String internal(UUID id) {
        return "Bearer "+Jwts.builder().subject("order-service").audience().add("payment-service").and()
                .claim("purpose","payment-status").claim("paymentId",id.toString()).expiration(Date.from(Instant.now().plusSeconds(30)))
                .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    String signature(String payload,String secret,long timestamp) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(StandardCharsets.UTF_8)));
    }
    String event="{\"id\":\"evt_signed_test\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_test\",\"object\":\"payment_intent\"}}}";

    @Test void invalidOrExpiredWebhookSignaturesNeverReachBusinessLogic() throws Exception {
        var mvc=mvc();
        mvc.perform(post("/api/v1/payments/webhook").contentType("application/json").header("Stripe-Signature",signature(event,"wrong",Instant.now().getEpochSecond())).content(event))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payments/webhook").contentType("application/json").header("Stripe-Signature",signature(event,WEBHOOK,Instant.now().minusSeconds(1000).getEpochSecond())).content(event))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(webhooks);
    }
    @Test void authenticStripeSignaturesWorkWithoutJwtAndAllowRedelivery() throws Exception {
        String sig=signature(event,WEBHOOK,Instant.now().getEpochSecond());var mvc=mvc();
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/payments/webhook").contentType("application/json").header("Stripe-Signature",sig).content(event))
                .andExpect(status().isOk());
        verify(webhooks,times(2)).handleEvent(any());
    }
    @Test void onlyExactPostWebhookIsExemptFromUserJwt() throws Exception {
        var mvc=mvc();
        mvc.perform(get("/api/v1/payments/webhook")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/webhook/child").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/webhook/").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments").header("X-User-Id",UUID.randomUUID()).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service,webhooks);
    }
    @Test void publicOperationsRequireVerifiedUserJwtAndIgnoreSpoofedIdentityHeaders() throws Exception {
        UUID owner=UUID.randomUUID(),order=UUID.randomUUID();
        when(service.createPayment(any(),any())).thenReturn(PaymentResponse.builder().initializationState(InitializationState.READY).build());
        var mvc=mvc();
        mvc.perform(post("/api/v1/payments").contentType("application/json").content("{\"orderId\":\""+order+"\"}")
                .header("Authorization",user(owner,Instant.now().plusSeconds(60))).header("X-User-Id",UUID.randomUUID()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"));
        var captor=ArgumentCaptor.forClass(PaymentActor.class);verify(service).createPayment(any(),captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(owner);
        mvc.perform(get("/api/v1/payments").header("Authorization",user(owner,Instant.now().minusSeconds(5))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments").header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
    @Test void internalStatusRejectsUserTokensAndRequiresExactPaymentScope() throws Exception {
        UUID id=UUID.randomUUID();var payment=Payment.builder().orderId(UUID.randomUUID()).userId(UUID.randomUUID()).amount(java.math.BigDecimal.TEN)
                .currency("INR").status(PaymentStatus.COMPLETED).build();payment.setId(id);payment.setTerminalVerified(true);
        when(repository.findById(id)).thenReturn(Optional.of(payment));var mvc=mvc();
        mvc.perform(get("/internal/v1/payments/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/payments/"+id).header("Authorization",user(payment.getUserId(),Instant.now().plusSeconds(60))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/payments/"+id).header("Authorization",internal(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/payments/"+id).header("Authorization",internal(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
        mvc.perform(get("/api/v1/payments").header("Authorization",internal(id))).andExpect(status().isUnauthorized());
        verify(repository,times(1)).findById(id);
    }
    @Test void uncertainInitializationReturns202AndReconciliationReturns409WithoutSecret() throws Exception {
        UUID id=UUID.randomUUID();var mvc=mvc();String token=user(id,Instant.now().plusSeconds(60));
        when(service.createPayment(any(),any())).thenReturn(PaymentResponse.builder().initializationState(InitializationState.UNKNOWN).build());
        mvc.perform(post("/api/v1/payments").header("Authorization",token).contentType("application/json").content("{\"orderId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.clientSecret").isEmpty());
        when(service.createPayment(any(),any())).thenReturn(PaymentResponse.builder().initializationState(InitializationState.RECONCILIATION_REQUIRED).build());
        mvc.perform(post("/api/v1/payments").header("Authorization",token).contentType("application/json").content("{\"orderId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
    }
}
