package com.orderflow.payment.producer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.event.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
@Component @RequiredArgsConstructor
public class PaymentEventProducer {
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper mapper;
    public void publishPaymentCompleted(PaymentCompletedEvent event) { send("payment.completed",event.getOrderId().toString(),event); }
    public void publishPaymentFailed(PaymentFailedEvent event) { send("payment.failed",event.getOrderId().toString(),event); }
    private void send(String topic,String key,Object event) {
        try { kafka.send(topic,key,mapper.writeValueAsString(event)).get(10,TimeUnit.SECONDS); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Payment notification interrupted");}
        catch(Exception failure){throw new IllegalStateException("Payment notification not acknowledged");}
    }
}
