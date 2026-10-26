package com.orderflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.event.PaymentCompletedEvent;
import com.orderflow.payment.producer.PaymentEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PaymentEventProducerTest {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    PaymentCompletedEvent event=PaymentCompletedEvent.builder().paymentId(UUID.randomUUID())
            .orderId(UUID.randomUUID()).userId(UUID.randomUUID()).amount(BigDecimal.TEN).build();
    @Test void successfulPublicationWaitsForAckAndUsesOrderKey() {
        when(kafka.send(eq("payment.completed"),eq(event.getOrderId().toString()),anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        new PaymentEventProducer(kafka,new ObjectMapper()).publishPaymentCompleted(event);
        verify(kafka).send(eq("payment.completed"),eq(event.getOrderId().toString()),contains(event.getPaymentId().toString()));
    }
    @Test void failedBrokerAckPropagatesWithoutLeakingUnderlyingDiagnostics() {
        when(kafka.send(anyString(),anyString(),anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sensitive diagnostic")));
        assertThatThrownBy(() -> new PaymentEventProducer(kafka,new ObjectMapper()).publishPaymentCompleted(event))
                .isInstanceOf(IllegalStateException.class).hasMessage("Payment notification not acknowledged").hasNoCause();
    }
    @Test void interruptedPublicationPreservesInterruptForCaller() {
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new PaymentEventProducer(kafka,new ObjectMapper()).publishPaymentCompleted(event))
                    .hasMessage("Payment notification interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
