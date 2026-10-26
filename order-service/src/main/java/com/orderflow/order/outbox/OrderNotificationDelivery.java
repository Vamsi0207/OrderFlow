package com.orderflow.order.outbox;

import com.orderflow.order.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class OrderNotificationDelivery {
    private final OrderNotificationRepository repository;
    private final KafkaTemplate<String,OrderCreatedEvent> kafka;
    @Transactional
    public void deliver(UUID id) {
        var n=repository.findLocked(id).orElseThrow();
        if(n.isPublished() || n.getAttempts()>=10 || n.getNextAttemptAt()!=null && n.getNextAttemptAt().isAfter(Instant.now()))return;
        n.setAttempts(n.getAttempts()+1);
        try {
            kafka.send("order.created",id.toString(),new OrderCreatedEvent(id,n.getUserId(),n.getAmount())).get(10,TimeUnit.SECONDS);
            n.setPublished(true);n.setLastError(null);n.setNextAttemptAt(null);
        } catch(Exception unavailable) {
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            n.setLastError(n.getAttempts()>=10?"PUBLICATION_EXHAUSTED":"PUBLICATION_RETRY");
            n.setNextAttemptAt(Instant.now().plusSeconds(Math.min(3600,30L << Math.min(n.getAttempts()-1,7))));
            log.warn("Order notification order={} attempt={} code={}",id,n.getAttempts(),n.getLastError());
        }
    }
}
