package com.orderflow.order.service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"eureka.client.enabled=false","spring.kafka.listener.auto-startup=false"})
class LegacyPaymentEffectStoreTest {
    @Autowired LegacyPaymentEffectStore store;
    @Autowired PlatformTransactionManager manager;
    @Test void completedStockEffectSurvivesOuterOrderRollbackAndIsNotRepeated() {
        UUID order=UUID.randomUUID(),product=UUID.randomUUID(),payment=UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            assertThat(store.claim(order,product,payment,1,true)).isTrue();
            store.complete(order,product);
            throw new IllegalStateException("order commit failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.claim(order,product,payment,1,true)).isFalse();
        assertThatThrownBy(() -> store.claim(order,product,payment,1,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void startedEffectRemainsBlockedAfterOuterRollback() {
        UUID order=UUID.randomUUID(),product=UUID.randomUUID(),payment=UUID.randomUUID();
        assertThat(store.claim(order,product,payment,1,false)).isTrue();
        assertThatThrownBy(() -> store.claim(order,product,payment,1,false)).hasMessageContaining("reconciliation");
    }
}
