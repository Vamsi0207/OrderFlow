package com.orderflow.order.consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.client.*;
import com.orderflow.order.entity.*;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"eureka.client.enabled=false","spring.kafka.listener.auto-startup=false"})
class PaymentEventConcurrencyTest {
    @Autowired PaymentEventConsumer consumer;
    @Autowired OrderRepository repository;
    @Autowired ObjectMapper mapper;
    @MockitoBean InventoryClient inventory;
    @MockitoBean PaymentStatusClient payments;
    @Test void concurrentDuplicateEventsSerializeOnOrderAndDeductOnce() throws Exception {
        var order=Order.builder().userId(UUID.randomUUID()).status(OrderStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        order.getOrderItems().add(OrderItem.builder().order(order).productId(UUID.randomUUID()).price(BigDecimal.TEN).quantity(1).build());
        repository.saveAndFlush(order);UUID payment=UUID.randomUUID();
        when(payments.get(payment)).thenReturn(new PaymentStatusClient.Status(payment,order.getId(),order.getUserId(),BigDecimal.TEN,"INR","COMPLETED"));
        var message=mapper.writeValueAsString(new PaymentEventConsumer.PaymentEvent(payment,order.getId(),order.getUserId(),BigDecimal.TEN));
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1);var ready=new CountDownLatch(4);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(() -> {ready.countDown();start.await();consumer.consumePaymentCompleted(message);return null;}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var f:futures)f.get(30,TimeUnit.SECONDS);
            assertThat(repository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            verify(inventory,times(1)).deductStock(any());
        } finally {repository.deleteById(order.getId());}
    }

    @Test void ambiguousLegacyInventoryCallIsNotRepeatedOnRedelivery() throws Exception {
        var order=Order.builder().userId(UUID.randomUUID()).status(OrderStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        order.getOrderItems().add(OrderItem.builder().order(order).productId(UUID.randomUUID()).price(BigDecimal.TEN).quantity(1).build());
        repository.saveAndFlush(order);UUID payment=UUID.randomUUID();
        when(payments.get(payment)).thenReturn(new PaymentStatusClient.Status(payment,order.getId(),order.getUserId(),BigDecimal.TEN,"INR","COMPLETED"));
        when(inventory.deductStock(any())).thenThrow(new IllegalStateException("ambiguous timeout"));
        var message=mapper.writeValueAsString(new PaymentEventConsumer.PaymentEvent(payment,order.getId(),order.getUserId(),BigDecimal.TEN));
        try {
            assertThatThrownBy(() -> consumer.consumePaymentCompleted(message)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> consumer.consumePaymentCompleted(message)).isInstanceOf(IllegalStateException.class).hasMessageContaining("reconciliation");
            verify(inventory,times(1)).deductStock(any());
            assertThat(repository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        } finally {repository.deleteById(order.getId());}
    }
}
