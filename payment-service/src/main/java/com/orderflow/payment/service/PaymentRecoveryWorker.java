package com.orderflow.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component @EnableScheduling @RequiredArgsConstructor @Slf4j
public class PaymentRecoveryWorker {
    private final PaymentRecoveryStore recovery;
    private final StripePaymentService stripe;
    private final PaymentWebhookStore outcomes;
    private final PaymentNotificationService notifications;
    @Value("${orderflow.recovery.enabled:true}") private boolean enabled;
    @Scheduled(fixedDelayString="${orderflow.recovery.delay-ms:30000}",initialDelayString="${orderflow.recovery.delay-ms:30000}")
    public void tick() { if(enabled)drain(); }
    public void drain() {
        try {for(var id:recovery.due())recover(id);}
        catch(Exception unavailable){log.warn("Payment recovery scan code=SCAN_UNAVAILABLE");}
    }
    public void recover(java.util.UUID id) {
        var work=recovery.claim(id);
        if(work==null)return;
        try {
            if(!work.notification()) {
                var current=stripe.retrieve(work.intentId());
                if(current==null || !work.intentId().equals(current.getId()))throw new IllegalStateException();
                if(!id.equals(outcomes.apply(current)))throw new IllegalStateException();
            }
            notifications.publish(id);
            recovery.succeeded(id,work.token());
        } catch(Exception unavailable) {
            recovery.failed(id,work.token());
            // No SDK exception/body, JWT, client secret or payment payload in logs.
            log.warn("Payment recovery payment={} code=RECOVERY_RETRY_OR_EXHAUSTED",id);
        }
    }
}
