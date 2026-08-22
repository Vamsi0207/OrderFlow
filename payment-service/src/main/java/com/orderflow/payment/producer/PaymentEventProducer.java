package com.orderflow.payment.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.orderflow.payment.event.PaymentFailedEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishPaymentCompleted(PaymentCompletedEvent event) {

        try {
            String message = objectMapper.writeValueAsString(event);

            kafkaTemplate.send("payment.completed", message);

            log.info("Published payment.completed event: {}", message);

        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Failed to serialize PaymentCompletedEvent", e);
        }
    }
    public void publishPaymentFailed(PaymentFailedEvent event) {

    try {
        String message = objectMapper.writeValueAsString(event);

        kafkaTemplate.send("payment.failed", message);

        log.info("Published payment.failed event: {}", message);

    } catch (JsonProcessingException e) {
        throw new RuntimeException(
                "Failed to serialize PaymentFailedEvent", e);
    }
}
}
