package com.orderflow.payment.service;
import com.orderflow.payment.enums.*;
import com.orderflow.payment.repository.PaymentRepository;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class PaymentWebhookStore {
    private final PaymentRepository repository;
    private final StripePaymentService stripe;
    @Transactional
    public UUID apply(PaymentIntent intent) {
        var known=repository.findIdByStripePaymentIntentId(intent.getId());
        // A verified webhook may arrive after Stripe created an intent but before our
        // initializer persisted its ID (including a timeout). Recover by frozen metadata.
        UUID id=known.orElseGet(() -> {
            if(intent.getMetadata()==null || intent.getMetadata().get("paymentId")==null)
                throw new IllegalStateException("Unknown payment intent");
            return UUID.fromString(intent.getMetadata().get("paymentId"));
        });
        var payment=repository.findLocked(id).orElseThrow();
        if(!stripe.matches(payment,intent) || (payment.getStripePaymentIntentId()!=null
                && !payment.getStripePaymentIntentId().equals(intent.getId())))throw new IllegalStateException("Payment intent snapshot mismatch");
        payment.setStripePaymentIntentId(intent.getId());
        payment.setInitializationState(InitializationState.READY);
        if(payment.getStatus()!=PaymentStatus.PENDING && payment.isTerminalVerified())return id;
        // Legacy terminal rows predate verified-state tracking. Only corroborate the
        // same terminal outcome; inconsistent legacy rows require reconciliation.
        if(payment.getStatus()==PaymentStatus.FAILED && !"canceled".equals(intent.getStatus()))return id;
        if(payment.getStatus()==PaymentStatus.COMPLETED && !"succeeded".equals(intent.getStatus()))return id;
        if("succeeded".equals(intent.getStatus())) {
            if(intent.getAmountReceived()==null || intent.getAmountReceived()!=StripePaymentService.minorUnits(payment.getAmount()))
                throw new IllegalStateException("Received payment amount mismatch");
            payment.setStatus(PaymentStatus.COMPLETED);payment.setTerminalVerified(true);
            payment.setPendingEvent("payment.completed");payment.setEventPublished(false);resetRecovery(payment);
        } else if("canceled".equals(intent.getStatus())) {
            payment.setStatus(PaymentStatus.FAILED);payment.setTerminalVerified(true);
            payment.setPendingEvent("payment.failed");payment.setEventPublished(false);resetRecovery(payment);
        }
        // requires_payment_method, requires_action and processing are unresolved.
        // payment_intent.payment_failed is retryable, not authorization to release stock.
        return id;
    }
    private void resetRecovery(com.orderflow.payment.entity.Payment payment) {
        // A newly verified terminal outcome is new outbox work, independent of a
        // prior exhausted Stripe-read budget. Redelivery never resets this budget.
        payment.setRecoveryAttempts(0);payment.setRecoveryNextAt(null);
        payment.setRecoveryToken(null);payment.setRecoveryError(null);
    }
}
