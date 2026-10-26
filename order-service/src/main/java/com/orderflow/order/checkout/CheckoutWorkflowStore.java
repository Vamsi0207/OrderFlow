package com.orderflow.order.checkout;
import com.orderflow.order.entity.*;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.dto.response.ProductInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
@Service @RequiredArgsConstructor
public class CheckoutWorkflowStore {
    private final CheckoutAttemptRepository attempts;
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    @Transactional
    public boolean claim(UUID id,UUID owner) {
        return jdbc.update("""
            update checkout_attempts set lease_owner=?,lease_until=current_timestamp+interval '60 seconds',
              version=version+1,updated_at=current_timestamp
            where id=? and (lease_until is null or lease_until<current_timestamp)
            """,owner,id)==1;
    }
    @Transactional
    public void heartbeat(UUID id,UUID owner) {
        if(jdbc.update("""
            update checkout_attempts set lease_until=current_timestamp+interval '60 seconds',version=version+1,updated_at=current_timestamp
            where id=? and lease_owner=? and lease_until>current_timestamp
            """,id,owner)!=1)throw new LeaseLostException();
    }
    @Transactional
    public void release(UUID id,UUID owner) {
        jdbc.update("update checkout_attempts set lease_owner=null,lease_until=null,version=version+1 where id=? and lease_owner=?",id,owner);
    }
    private CheckoutAttempt owned(UUID id,UUID owner) {
        var attempt=attempts.findLocked(id).orElseThrow();
        // PostgreSQL time is authoritative for fencing, including after process restart.
        if(!owner.equals(attempt.getLeaseOwner()))throw new LeaseLostException();
        Boolean valid=jdbc.queryForObject("select lease_until>current_timestamp from checkout_attempts where id=? and lease_owner=?",Boolean.class,id,owner);
        if(!owner.equals(attempt.getLeaseOwner()) || !Boolean.TRUE.equals(valid))throw new LeaseLostException();
        return attempt;
    }
    @Transactional
    public UUID createOrder(UUID id,UUID owner,SortedMap<UUID,Integer> quantities,Map<UUID,ProductInfoResponse> products) {
        var attempt=owned(id,owner);
        if(attempt.getOrderId()!=null)return attempt.getOrderId();
        var order=Order.builder().checkoutAttemptId(id).scopedReservation(true).userId(attempt.getUserId())
                .status(OrderStatus.PENDING).totalAmount(BigDecimal.ZERO).build();
        BigDecimal total=BigDecimal.ZERO;
        for(var line:quantities.entrySet()) {
            var product=products.get(line.getKey());
            BigDecimal price=product.getPrice();
            total=total.add(price.multiply(BigDecimal.valueOf(line.getValue())));
            order.getOrderItems().add(OrderItem.builder().order(order).productId(line.getKey()).quantity(line.getValue()).price(price).build());
        }
        if(total.signum()<=0 || total.compareTo(new BigDecimal("99999999.99"))>0)throw new IllegalArgumentException("Order total is outside supported range");
        order.setTotalAmount(total);
        orders.saveAndFlush(order);
        attempt.setOrderId(order.getId());attempt.setStatus(CheckoutAttempt.Status.ORDER_CREATED);
        attempt.setLastError(null);attempt.setLastHttpStatus(null);
        return order.getId(); // order plus attempt association commit atomically
    }
    @Transactional
    public void reserved(UUID id,UUID owner) {
        var a=owned(id,owner);
        var order=orders.findById(a.getOrderId()).orElseThrow();
        order.setInventoryReserved(true);
        a.setStatus(CheckoutAttempt.Status.INVENTORY_RESERVED);a.setLastError(null);a.setLastHttpStatus(null);
    }
    @Transactional
    public void payment(UUID id,UUID owner,CheckoutPaymentClient.Payment payment,int http) {
        var a=owned(id,owner);
        if(payment!=null && payment.id()!=null) {
            if(a.getPaymentId()!=null && !a.getPaymentId().equals(payment.id()))throw new IllegalStateException("Payment identity changed");
            a.setPaymentId(payment.id());a.setPaymentStatus(payment.status());a.setPaymentInitializationState(payment.initializationState());
        }
        if(http==409) {a.setStatus(CheckoutAttempt.Status.RECOVERY_REQUIRED);a.setLastError("PAYMENT_RECONCILIATION_REQUIRED");}
        else if(http==201) {a.setStatus(CheckoutAttempt.Status.COMPLETED);a.setLastError(null);}
        else {a.setStatus(CheckoutAttempt.Status.INVENTORY_RESERVED);a.setLastError("PAYMENT_PENDING");}
        a.setLastHttpStatus(http);a.setNextRetryAt(http==202?Instant.now().plusSeconds(2):null);
    }
    @Transactional
    public void terminal(UUID id,UUID owner,CheckoutPaymentClient.Payment payment,boolean cancelled) {
        var a=owned(id,owner);a.setPaymentId(payment.id());a.setPaymentStatus(payment.status());
        a.setPaymentInitializationState(payment.initializationState());a.setStatus(CheckoutAttempt.Status.COMPLETED);
        a.setLastError(cancelled?"ORDER_CANCELLED":null);a.setLastHttpStatus(cancelled?409:200);
    }
    @Transactional
    public void reconcile(UUID id,UUID owner,String code) {
        var a=owned(id,owner);a.setStatus(CheckoutAttempt.Status.RECOVERY_REQUIRED);a.setLastError(code);a.setLastHttpStatus(409);
    }
    @Transactional
    public void failure(UUID id,UUID owner,int http,String code) {
        var a=owned(id,owner);a.setLastError(code);a.setLastHttpStatus(http);a.setRetryCount(a.getRetryCount()+1);
        a.setNextRetryAt(http==202 || http==503?Instant.now().plusSeconds(2):null);
    }
    @Transactional(readOnly=true)
    public BuyNowResponse snapshot(UUID id,String code,CheckoutPaymentClient.Payment live) {
        var a=attempts.findById(id).orElseThrow();
        var o=a.getOrderId()==null?null:orders.findWithOrderItemsById(a.getOrderId()).orElseThrow();
        return new BuyNowResponse(id,a.getStatus().name(),code==null?a.getLastError():code,a.getOrderId(),o==null?null:o.getStatus().name(),
                o==null?null:o.getTotalAmount(),o==null?null:o.getCurrency(),o==null?List.of():o.getOrderItems().stream()
                    .map(i -> new BuyNowResponse.Item(i.getProductId(),i.getQuantity(),i.getPrice())).toList(),
                new BuyNowResponse.Payment(live!=null?live.id():a.getPaymentId(),live!=null?live.status():a.getPaymentStatus(),
                    live!=null?live.initializationState():a.getPaymentInitializationState(),live==null?null:live.clientSecret()));
    }
    @Transactional(readOnly=true)
    public CheckoutAttempt get(UUID id) {return attempts.findById(id).orElseThrow();}
}
