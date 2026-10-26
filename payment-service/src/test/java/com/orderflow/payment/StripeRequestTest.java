package com.orderflow.payment;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.service.StripePaymentService;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class StripeRequestTest {
    @Test void sdkReceivesStableKeyFrozenAmountCurrencyAndRecoverableMetadata() throws Exception {
        var p=Payment.builder().orderId(UUID.randomUUID()).userId(UUID.randomUUID()).amount(new BigDecimal("12.50")).currency("USD").build();p.setId(UUID.randomUUID());
        var service=new StripePaymentService();
        try(var sdk=mockStatic(PaymentIntent.class)) {
            sdk.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),any(RequestOptions.class))).thenAnswer(call -> {
                PaymentIntentCreateParams params=call.getArgument(0);RequestOptions options=call.getArgument(1);
                assertThat(params.getAmount()).isEqualTo(1250L);assertThat(params.getCurrency()).isEqualTo("usd");
                assertThat(params.getMetadata()).containsEntry("paymentId",p.getId().toString());
                assertThat(options.getIdempotencyKey()).isEqualTo("orderflow:payment:"+p.getId()+":v1");
                return new PaymentIntent();
            });
            service.create(p);service.create(p);
            sdk.verify(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class),any(RequestOptions.class)),times(2));
        }
    }
}
