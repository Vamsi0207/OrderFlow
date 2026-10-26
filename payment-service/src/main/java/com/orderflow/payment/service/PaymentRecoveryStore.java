package com.orderflow.payment.service;

import com.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Durable scheduling for the payment-row outbox and known-intent reconciliation. */
@Service @RequiredArgsConstructor
public class PaymentRecoveryStore {
    private final PaymentRepository repository;
    private final JdbcTemplate jdbc;
    public record Work(UUID id,UUID token,String intentId,boolean notification) {}
    @Transactional(readOnly=true)
    public List<UUID> due() {
        return jdbc.queryForList("""
            select id from payments where recovery_attempts<10
              and (recovery_next_at is null or recovery_next_at<=current_timestamp)
              and ((pending_event is not null and not event_published)
                   or (not terminal_verified and stripe_payment_intent_id is not null))
            order by case when pending_event is not null and not event_published then 0 else 1 end,
              recovery_next_at nulls first,id limit 20
            """,UUID.class);
    }
    @Transactional
    public Work claim(UUID id) {
        var p=repository.findLocked(id).orElseThrow();
        if(p.getRecoveryAttempts()>=10 || p.getRecoveryNextAt()!=null && p.getRecoveryNextAt().isAfter(Instant.now()))return null;
        boolean notification=p.getPendingEvent()!=null && !p.isEventPublished();
        if(!notification && (p.isTerminalVerified() || p.getStripePaymentIntentId()==null))return null;
        p.setRecoveryAttempts(p.getRecoveryAttempts()+1);
        // Commit the claim BEFORE remote work. Multiple service instances coordinate
        // on the database row; a dead worker becomes eligible again after 60 seconds.
        p.setRecoveryNextAt(Instant.now().plusSeconds(60));
        p.setRecoveryToken(UUID.randomUUID());
        return new Work(id,p.getRecoveryToken(),p.getStripePaymentIntentId(),notification);
    }
    @Transactional
    public void succeeded(UUID id,UUID token) {
        var p=repository.findLocked(id).orElseThrow();
        if(!token.equals(p.getRecoveryToken()))return;
        p.setRecoveryAttempts(0);p.setRecoveryError(null);
        // Healthy unresolved intents are polled at most once a minute, never released.
        p.setRecoveryNextAt(Instant.now().plusSeconds(60));
    }
    @Transactional
    public void failed(UUID id,UUID token) {
        var p=repository.findLocked(id).orElseThrow();
        if(!token.equals(p.getRecoveryToken()))return;
        p.setRecoveryError(p.getRecoveryAttempts()>=10?"RECOVERY_EXHAUSTED":"RECOVERY_RETRY");
        p.setRecoveryNextAt(Instant.now().plusSeconds(Math.min(3600,30L << Math.min(p.getRecoveryAttempts()-1,7))));
    }
}
