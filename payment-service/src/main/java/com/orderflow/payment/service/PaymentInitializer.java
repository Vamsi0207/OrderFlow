package com.orderflow.payment.service;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.*;
import com.orderflow.payment.repository.PaymentRepository;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class PaymentInitializer {
    private final PaymentRepository repository;
    private final StripePaymentService stripe;
    @Transactional
    public PaymentResponse initialize(UUID id) {
        var payment=repository.findLocked(id).orElseThrow();
        if(payment.getStatus()!=PaymentStatus.PENDING)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Payment is already terminal");
        if(payment.getInitializationState()==InitializationState.RECONCILIATION_REQUIRED)return response(payment,null);
        try {
            boolean known=payment.getStripePaymentIntentId()!=null;
            if(!known && (payment.getInitializationStartedAt()==null ||
                    !payment.getInitializationStartedAt().isAfter(Instant.now().minus(Duration.ofHours(23))))) {
                payment.setInitializationState(InitializationState.RECONCILIATION_REQUIRED);
                payment.setLastInitializationError("IDEMPOTENCY_WINDOW_EXPIRED");
                return response(payment,null);
            }
            var intent=known?stripe.retrieve(payment.getStripePaymentIntentId()):stripe.create(payment);
            if(!stripe.matches(payment,intent) || (known && !payment.getStripePaymentIntentId().equals(intent.getId()))) {
                payment.setInitializationState(InitializationState.RECONCILIATION_REQUIRED);
                payment.setLastInitializationError("STRIPE_SNAPSHOT_MISMATCH");return response(payment,null);
            }
            payment.setStripePaymentIntentId(intent.getId());
            payment.setInitializationState(InitializationState.READY);
            payment.setLastInitializationError(null);
            // Creation/retrieval alone never marks success or emits payment.failed.
            boolean active=java.util.Set.of("requires_payment_method","requires_confirmation","requires_action","processing").contains(intent.getStatus());
            if(active && (intent.getClientSecret()==null || intent.getClientSecret().isBlank())) {
                payment.setInitializationState(InitializationState.UNKNOWN);
                payment.setLastInitializationError("CLIENT_SECRET_UNAVAILABLE");
                return response(payment,null);
            }
            return response(payment,active?intent.getClientSecret():null);
        } catch(StripeException | RuntimeException ambiguous) {
            payment.setInitializationState(InitializationState.UNKNOWN);
            payment.setLastInitializationError("STRIPE_RESULT_UNKNOWN");
            // Do not log exception objects: SDK diagnostics may include sensitive response data.
            return response(payment,null);
        }
    }
    public static PaymentResponse response(Payment payment,String clientSecret) {
        return PaymentResponse.builder().id(payment.getId()).orderId(payment.getOrderId()).userId(payment.getUserId())
                .amount(payment.getAmount()).currency(payment.getCurrency()).status(payment.verifiedStatus())
                .initializationState(!payment.isTerminalVerified() && payment.getStatus()!=PaymentStatus.PENDING
                        ?InitializationState.RECONCILIATION_REQUIRED:payment.getInitializationState()).createdAt(payment.getCreatedAt())
                .stripePaymentIntentId(payment.getStripePaymentIntentId()).clientSecret(clientSecret).build();
    }
}
