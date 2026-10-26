package com.orderflow.order.consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.client.*;
import com.orderflow.order.dto.inventory.request.InventoryReservationRequest;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import lombok.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
@Component @RequiredArgsConstructor
public class PaymentEventConsumer {
    private final OrderRepository orders;
    private final InventoryClient inventory;
    private final ObjectMapper mapper;
    private final PaymentStatusClient payments;
    private final com.orderflow.order.service.LegacyPaymentEffectStore legacyEffects;
    @KafkaListener(topics="payment.completed",groupId="order-service")
    @Transactional
    public void consumePaymentCompleted(String message) { process(message,true); }
    @KafkaListener(topics="payment.failed",groupId="order-service")
    @Transactional
    public void consumePaymentFailed(String message) { process(message,false); }
    private void process(String message,boolean completed) {
        try {
            var event=mapper.readValue(message,PaymentEvent.class);
            var order=orders.findLocked(event.orderId()).orElseThrow();
            // Includes DELIVERED: never cancel or resurrect a terminal order.
            if(order.getStatus()!=OrderStatus.PENDING)return;
            var payment=payments.get(event.paymentId());
            if(payment==null || !event.paymentId().equals(payment.id()) || !order.getId().equals(payment.orderId())
                    || !order.getUserId().equals(payment.userId()) || payment.amount()==null
                    || order.getTotalAmount().compareTo(payment.amount())!=0 || !order.getCurrency().equalsIgnoreCase(payment.currency()))
                throw new IllegalStateException("Payment/order snapshot mismatch");
            // Topic/body alone is never proof of a terminal payment. Unknown/pending outcomes keep inventory reserved.
            if(!(completed?"COMPLETED":"FAILED").equals(payment.status()))return;
            if(order.isScopedReservation() || inventory.hasScopedReservation(order.getId()))inventory.finishScoped(order.getId(),completed);
            else {
                var quantities=new java.util.TreeMap<UUID,Integer>();
                for(var item:order.getOrderItems())quantities.merge(item.getProductId(),item.getQuantity(),Math::addExact);
                for(var line:quantities.entrySet()) {
                    if(!legacyEffects.claim(order.getId(),line.getKey(),payment.id(),line.getValue(),completed))continue;
                    var request=InventoryReservationRequest.builder().productId(line.getKey()).quantity(line.getValue()).build();
                    // A timeout/exception leaves STARTED durable. Redelivery stops for reconciliation.
                    if(completed)inventory.deductStock(request);else inventory.releaseStock(request);
                    legacyEffects.complete(order.getId(),line.getKey());
                }
            }
            order.setStatus(completed?OrderStatus.CONFIRMED:OrderStatus.CANCELLED);
            orders.save(order);
        } catch(RuntimeException failure) {throw failure;}
        catch(Exception failure) {throw new IllegalStateException("Payment event processing requires retry");}
    }
    public record PaymentEvent(UUID paymentId,UUID orderId,UUID userId,BigDecimal amount) {}
}
