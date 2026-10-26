package com.orderflow.order.outbox;

import com.orderflow.order.event.OrderCreatedEvent;
import com.orderflow.order.producer.OrderEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"orderflow.recovery.enabled=false","spring.kafka.listener.auto-startup=false","eureka.client.enabled=false"})
class OrderNotificationTest {
    @Autowired OrderEventProducer producer;
    @Autowired OrderNotificationRepository repository;
    @Autowired OrderNotificationDelivery delivery;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean(name="kafkaTemplate") KafkaTemplate<String,OrderCreatedEvent> kafka;
    UUID enqueue() {
        UUID id=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> producer.publishOrderCreated(new OrderCreatedEvent(id,UUID.randomUUID(),BigDecimal.TEN)));
        return id;
    }
    @Test void messageRollsBackWithOrderTransactionAndEnqueueNeverCallsBroker() {
        UUID id=UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            producer.publishOrderCreated(new OrderCreatedEvent(id,UUID.randomUUID(),BigDecimal.TEN));tx.setRollbackOnly();
        });
        assertThat(repository.findById(id)).isEmpty();verifyNoInteractions(kafka);
    }
    @Test void brokerFailurePersistsBackoffAndRestartedDeliveryPublishesOnce() {
        UUID id=enqueue();
        try {
            when(kafka.send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
            delivery.deliver(id);delivery.deliver(id);
            var n=repository.findById(id).orElseThrow();
            assertThat(n.isPublished()).isFalse();assertThat(n.getAttempts()).isEqualTo(1);
            assertThat(n.getLastError()).isEqualTo("PUBLICATION_RETRY");assertThat(n.getNextAttemptAt()).isAfter(Instant.now());
            n.setNextAttemptAt(Instant.now().minusSeconds(1));repository.saveAndFlush(n);
            // No in-memory pending state is necessary to resume.
            delivery.deliver(id);delivery.deliver(id);
            assertThat(repository.findById(id).orElseThrow().isPublished()).isTrue();
            verify(kafka,times(2)).send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class));
        } finally {repository.deleteById(id);}
    }
    @Test void retryBudgetStopsAndPreservesMessageForOperatorRequeue() {
        UUID id=enqueue();
        try {
            var n=repository.findById(id).orElseThrow();n.setAttempts(9);repository.saveAndFlush(n);
            when(kafka.send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
            delivery.deliver(id);delivery.deliver(id);
            n=repository.findById(id).orElseThrow();assertThat(n.getAttempts()).isEqualTo(10);
            assertThat(n.getLastError()).isEqualTo("PUBLICATION_EXHAUSTED");assertThat(n.isPublished()).isFalse();
            assertThat(repository.due()).doesNotContain(id);
            verify(kafka,times(1)).send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class));
        } finally {repository.deleteById(id);}
    }

    @Test void concurrentDrainersSerializeOnDurableMessage() throws Exception {
        UUID id=enqueue();
        try {
            when(kafka.send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class)))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<4;i++)jobs.add(pool.submit(() -> {start.await();delivery.deliver(id);return null;}));
                start.countDown();for(var job:jobs)job.get(20,java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(repository.findById(id).orElseThrow().isPublished()).isTrue();
            verify(kafka,times(1)).send(eq("order.created"),eq(id.toString()),any(OrderCreatedEvent.class));
        } finally {repository.deleteById(id);}
    }
}
