package com.orderflow.order.config;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.time.Duration;

/** Failed records remain inspectable/replayable instead of being discarded after retries. */
@Configuration
public class PaymentEventRetryConfiguration {
    @Bean
    DefaultErrorHandler paymentEventErrorHandler(
            @org.springframework.beans.factory.annotation.Qualifier("eventRecoveryKafkaTemplate") KafkaTemplate<String,String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        // Initial delivery + four retries; an unavailable DLT never acknowledges recovery.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 4L));
    }
    @Bean
    KafkaTemplate<String,String> eventRecoveryKafkaTemplate(
            @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        var config=new java.util.HashMap<String,Object>();
        config.put("bootstrap.servers",bootstrap);
        config.put("key.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
        config.put("value.serializer",org.apache.kafka.common.serialization.StringSerializer.class);
        config.put("max.block.ms",5000);config.put("delivery.timeout.ms",10000);config.put("request.timeout.ms",5000);
        return new KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(config));
    }
    @Bean NewTopic completedPaymentDlt() { return TopicBuilder.name("payment.completed.DLT").partitions(1).replicas(1).build(); }
    @Bean NewTopic failedPaymentDlt() { return TopicBuilder.name("payment.failed.DLT").partitions(1).replicas(1).build(); }
}
