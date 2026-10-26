package com.orderflow.payment.service;
import com.orderflow.payment.client.AuthoritativeOrder;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Payment prepare(AuthoritativeOrder order) {
        jdbc.update("""
            INSERT INTO payments (id,order_id,user_id,amount,currency,status,initialization_state,
                initialization_started_at,event_published,version,created_at,updated_at)
            VALUES (?,?,?,?,?,'PENDING','INITIALIZING',CURRENT_TIMESTAMP,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON CONFLICT (order_id) DO NOTHING
            """,UUID.randomUUID(),order.id(),order.userId(),order.totalAmount(),order.currency().toUpperCase(java.util.Locale.ROOT));
        return repository.findByOrderId(order.id()).orElseThrow();
    }
}
