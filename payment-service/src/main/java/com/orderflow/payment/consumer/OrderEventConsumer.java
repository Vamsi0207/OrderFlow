package com.orderflow.payment.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderEventConsumer {

    @KafkaListener(
            topics = "order.created",
            groupId = "payment-service"
    )
    public void consumeOrderCreated(String message) {

        log.info("Received OrderCreatedEvent: {}", message);
    }
}