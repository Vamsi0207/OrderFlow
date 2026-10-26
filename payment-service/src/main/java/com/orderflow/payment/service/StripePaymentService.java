package com.orderflow.payment.service;
import com.orderflow.payment.entity.Payment;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.stereotype.Service;
import java.util.Locale;
@Service
public class StripePaymentService {
    public static long minorUnits(java.math.BigDecimal amount) { return amount.movePointRight(2).longValueExact(); }
    public String idempotencyKey(Payment payment) { return "orderflow:payment:"+payment.getId()+":v1"; }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).setConnectTimeout(5000).setReadTimeout(10000).setMaxNetworkRetries(0).build();
    }
    public PaymentIntent create(Payment payment) throws StripeException {
        var params=PaymentIntentCreateParams.builder().setAmount(minorUnits(payment.getAmount()))
                .setCurrency(payment.getCurrency().toLowerCase(Locale.ROOT))
                .putMetadata("orderId",payment.getOrderId().toString())
                .putMetadata("paymentId",payment.getId().toString())
                .putMetadata("userId",payment.getUserId().toString()).build();
        return PaymentIntent.create(params,options(idempotencyKey(payment)));
    }
    public PaymentIntent retrieve(String id) throws StripeException { return PaymentIntent.retrieve(id,options(null)); }
    public boolean matches(Payment p,PaymentIntent intent) {
        if(intent==null || intent.getId()==null || intent.getAmount()==null
                || intent.getAmount()!=minorUnits(p.getAmount()) || !p.getCurrency().equalsIgnoreCase(intent.getCurrency()))return false;
        var metadata=intent.getMetadata();
        if(metadata==null || !p.getOrderId().toString().equals(metadata.get("orderId")))return false;
        // Existing intents predate paymentId/userId metadata. Require both for all new attempts.
        return p.getInitializationStartedAt()==null || (p.getId().toString().equals(metadata.get("paymentId"))
                && p.getUserId().toString().equals(metadata.get("userId")));
    }
}
