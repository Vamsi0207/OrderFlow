package com.orderflow.order.outbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
@Component @EnableScheduling @RequiredArgsConstructor @Slf4j
public class OrderNotificationWorker {
    private final OrderNotificationRepository repository;
    private final OrderNotificationDelivery delivery;
    @Value("${orderflow.recovery.enabled:true}") private boolean enabled;
    @Scheduled(fixedDelayString="${orderflow.recovery.delay-ms:30000}",initialDelayString="${orderflow.recovery.delay-ms:30000}")
    public void tick() {
        if(!enabled)return;
        try {for(var id:repository.due())delivery.deliver(id);}
        catch(Exception unavailable){log.warn("Order notification scan code=SCAN_UNAVAILABLE");}
    }
}
