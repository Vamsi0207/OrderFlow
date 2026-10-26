package com.orderflow.payment.service;
import com.stripe.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class PaymentWebhookService {
    private final StripePaymentService stripe;
    private final PaymentWebhookStore store;
    private final PaymentNotificationService notifications;
    public void handleEvent(Event event) {
        if(!java.util.Set.of("payment_intent.succeeded","payment_intent.payment_failed","payment_intent.canceled","payment_intent.processing")
                .contains(event.getType()))return;
        try {
            var object=event.getDataObjectDeserializer().deserializeUnsafe();
            if(!(object instanceof PaymentIntent received))throw new IllegalStateException("Unexpected Stripe event object");
            var current=stripe.retrieve(received.getId());
            if(current==null || !received.getId().equals(current.getId()))throw new IllegalStateException("Invalid Stripe response");
            var paymentId=store.apply(current); // committed before broker publication
            notifications.publish(paymentId);
        } catch(Exception failure) {
            // Signal retry to Stripe without exposing SDK exceptions or client secrets.
            throw new IllegalStateException("Payment webhook requires retry or reconciliation");
        }
    }
}
