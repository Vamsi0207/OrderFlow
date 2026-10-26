package com.orderflow.payment.service;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.producer.PaymentEventProducer;
import com.orderflow.payment.event.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class PaymentNotificationService {
    private final PaymentRepository repository;
    private final PaymentEventProducer producer;
    /** A durable pending flag permits webhook redelivery to repair a broker failure.
     * A crash after broker ACK can republish; consumers must remain idempotent.
     */
    @Transactional
    public void publish(UUID id) {
        var p=repository.findLocked(id).orElseThrow();
        if(p.isEventPublished() || p.getPendingEvent()==null)return;
        if("payment.completed".equals(p.getPendingEvent()))producer.publishPaymentCompleted(PaymentCompletedEvent.builder()
                .paymentId(p.getId()).orderId(p.getOrderId()).userId(p.getUserId()).amount(p.getAmount()).build());
        else if("payment.failed".equals(p.getPendingEvent()))producer.publishPaymentFailed(PaymentFailedEvent.builder()
                .paymentId(p.getId()).orderId(p.getOrderId()).userId(p.getUserId()).amount(p.getAmount()).build());
        else throw new IllegalStateException("Unknown payment notification");
        p.setEventPublished(true);
    }
}
