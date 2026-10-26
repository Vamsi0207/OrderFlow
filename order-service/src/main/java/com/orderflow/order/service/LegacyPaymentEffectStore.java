package com.orderflow.order.service;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class LegacyPaymentEffectStore {
    private final JdbcTemplate jdbc;
    /** Persist before an anonymous remote mutation. A previously STARTED operation
     * has an ambiguous result and must be reconciled, never blindly repeated.
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean claim(UUID order,UUID product,UUID payment,int quantity,boolean completedPayment) {
        int inserted=jdbc.update("""
                insert into legacy_payment_effects (order_id,product_id,payment_id,quantity,completed_payment,state,updated_at)
                values (?,?,?,?,?,'STARTED',current_timestamp) on conflict (order_id,product_id) do nothing
                """,order,product,payment,quantity,completedPayment);
        if(inserted==1)return true;
        var prior=jdbc.queryForMap("select * from legacy_payment_effects where order_id=? and product_id=?",order,product);
        if(!payment.equals(prior.get("payment_id")) || quantity!=((Number)prior.get("quantity")).intValue()
                || completedPayment!=(Boolean)prior.get("completed_payment"))
            throw new IllegalStateException("Legacy inventory operation snapshot mismatch; reconciliation required");
        if(!"COMPLETED".equals(prior.get("state")))
            throw new IllegalStateException("Legacy inventory outcome is unknown; reconciliation required");
        return false;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(UUID order,UUID product) {
        if(jdbc.update("update legacy_payment_effects set state='COMPLETED',updated_at=current_timestamp where order_id=? and product_id=? and state='STARTED'",order,product)!=1)
            throw new IllegalStateException("Legacy inventory completion could not be recorded");
    }
}
