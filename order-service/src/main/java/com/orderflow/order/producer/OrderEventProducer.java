package com.orderflow.order.producer;

import com.orderflow.order.event.OrderCreatedEvent;
import com.orderflow.order.outbox.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component @RequiredArgsConstructor
public class OrderEventProducer {
    private final OrderNotificationRepository notifications;
    @Transactional(propagation=Propagation.MANDATORY)
    public void publishOrderCreated(OrderCreatedEvent event) {
        var n=new OrderNotification();
        n.setOrderId(event.getOrderId());n.setUserId(event.getUserId());n.setAmount(event.getAmount());
        notifications.save(n);
    }
}
