package com.orderflow.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.client.InventoryClient;
import com.orderflow.order.dto.inventory.request.InventoryReservationRequest;
import com.orderflow.order.entity.Order;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "payment.completed",
            groupId = "order-service"
    )
    public void consumePaymentCompleted(String message) {

        try {
            PaymentEvent event =
                    objectMapper.readValue(message, PaymentEvent.class);

            Order order = orderRepository
                    .findWithOrderItemsById(event.getOrderId())
                    .orElseThrow(() ->
                            new RuntimeException(
                                    "Order not found: " + event.getOrderId()));

            /*
             * Kafka provides at-least-once delivery.
             * The same payment event may be delivered again.
             *
             * If the order is already CONFIRMED,
             * inventory was already deducted.
             */
            if (order.getStatus() == OrderStatus.CONFIRMED) {

                log.info(
                        "Duplicate payment.completed event ignored. " +
                                "Order {} is already CONFIRMED",
                        event.getOrderId()
                );

                return;
            }

            /*
             * Do not allow a completed payment to confirm
             * an already cancelled order.
             */
            if (order.getStatus() == OrderStatus.CANCELLED) {

                log.warn(
                        "Ignoring payment.completed event for cancelled order: {}",
                        event.getOrderId()
                );

                return;
            }

            order.getOrderItems().forEach(item -> {

                InventoryReservationRequest request =
                        InventoryReservationRequest.builder()
                                .productId(item.getProductId())
                                .quantity(item.getQuantity())
                                .build();

                inventoryClient.deductStock(request);
            });

            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);

            log.info(
                    "Order {} confirmed after successful payment",
                    event.getOrderId()
            );

        } catch (Exception e) {

            log.error(
                    "Failed to process payment.completed event",
                    e
            );
        }
    }

    @KafkaListener(
            topics = "payment.failed",
            groupId = "order-service"
    )
    public void consumePaymentFailed(String message) {

        try {
            PaymentEvent event =
                    objectMapper.readValue(message, PaymentEvent.class);

            Order order = orderRepository
                    .findWithOrderItemsById(event.getOrderId())
                    .orElseThrow(() ->
                            new RuntimeException(
                                    "Order not found: " + event.getOrderId()));

            /*
             * If already CANCELLED, stock has already been released.
             */
            if (order.getStatus() == OrderStatus.CANCELLED) {

                log.info(
                        "Duplicate payment.failed event ignored. " +
                                "Order {} is already CANCELLED",
                        event.getOrderId()
                );

                return;
            }

            /*
             * Do not cancel an already confirmed order.
             */
            if (order.getStatus() == OrderStatus.CONFIRMED) {

                log.warn(
                        "Ignoring payment.failed event for confirmed order: {}",
                        event.getOrderId()
                );

                return;
            }

            order.getOrderItems().forEach(item -> {

                InventoryReservationRequest request =
                        InventoryReservationRequest.builder()
                                .productId(item.getProductId())
                                .quantity(item.getQuantity())
                                .build();

                inventoryClient.releaseStock(request);
            });

            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);

            log.info(
                    "Order {} cancelled after failed payment",
                    event.getOrderId()
            );

        } catch (Exception e) {

            log.error(
                    "Failed to process payment.failed event",
                    e
            );
        }
    }

    @Getter
    @NoArgsConstructor
    private static class PaymentEvent {

        private UUID paymentId;
        private UUID orderId;
        private UUID userId;
        private BigDecimal amount;
    }
}
