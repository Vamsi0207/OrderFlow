package com.orderflow.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.event.OrderCreatedEvent;
import com.orderflow.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "order.created",
            groupId = "payment-service"
    )
    public void consumeOrderCreated(String message) {

        try {
            OrderCreatedEvent event =
                    objectMapper.readValue(message, OrderCreatedEvent.class);

            CreatePaymentRequest request =
                    CreatePaymentRequest.builder()
                            .orderId(event.getOrderId())
                            .amount(event.getAmount())
                            .build();

            PaymentResponse payment =
                    paymentService.createPayment(
                            request,
                            event.getUserId()
                    );

            log.info(
                    "Order event processed successfully. Order: {}, Payment: {}, Status: {}",
                    event.getOrderId(),
                    payment.getId(),
                    payment.getStatus()
            );

        } catch (Exception e) {

            log.error(
                    "Failed to process OrderCreatedEvent: {}",
                    message,
                    e
            );
        }
    }
}
