package com.orderflow.order.checkout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class CheckoutAttemptTest {
    @Autowired CheckoutAttemptService service;
    @Autowired CheckoutAttemptRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private CheckoutRequest request(UUID product, int quantity) {
        return new CheckoutRequest(List.of(new CheckoutRequest.Item(product, quantity)));
    }

    @Test void duplicateNormalizedIntentReusesDurableAttempt() {
        UUID user = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var first = service.begin(user, "key", new CheckoutRequest(List.of(
                new CheckoutRequest.Item(a, 1), new CheckoutRequest.Item(b, 2), new CheckoutRequest.Item(a, 2))));
        var replay = service.begin(user, "key", new CheckoutRequest(List.of(
                new CheckoutRequest.Item(b, 2), new CheckoutRequest.Item(a, 3))));
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(repository.findById(first.getId()).orElseThrow().getNormalizedPayload()).contains(a + ":3");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getUpdatedAt()).isNotNull();
        assertThat(first.getStatus()).isEqualTo(CheckoutAttempt.Status.CREATED);
    }

    @Test void changedPayloadConflictsAndDifferentUsersHaveIndependentKeys() {
        UUID user = UUID.randomUUID(), product = UUID.randomUUID();
        var first = service.begin(user, "key", request(product, 1));
        assertThatThrownBy(() -> service.begin(user, "key", request(product, 2)))
                .isInstanceOf(CheckoutConflictException.class);
        assertThat(service.begin(UUID.randomUUID(), "key", request(product, 2)).getId()).isNotEqualTo(first.getId());
        assertThat(service.begin(user, "key", request(product, 1)).getId()).isEqualTo(first.getId());
    }

    @Test void simultaneousIdenticalRequestsCreateOneRow() throws Exception {
        UUID user = UUID.randomUUID(), product = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(8);
            var futures = new ArrayList<Future<UUID>>();
            for (int i=0; i<8; i++) futures.add(pool.submit(() -> {
                ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.begin(user, "race", request(product, 1)).getId();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var ids = new HashSet<UUID>();
            for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from checkout_attempts where user_id=?", Integer.class, user)).isEqualTo(1);
        }
    }

    @Test void simultaneousDifferentPayloadsHaveOneWinner() throws Exception {
        UUID user=UUID.randomUUID(), product=UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(2);
            var futures = new ArrayList<Future<Boolean>>();
            for (int quantity : List.of(1,2)) futures.add(pool.submit(() -> {
                ready.countDown(); start.await();
                try { service.begin(user, "race", request(product, quantity)); return true; }
                catch (CheckoutConflictException expected) { return false; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int successes=0;
            for (var f:futures) if (f.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test void databaseConstraintRejectsDirectDuplicateAndRecoveryDataSurvivesReload() {
        UUID user=UUID.randomUUID();
        var attempt=service.begin(user, "constraint", request(UUID.randomUUID(),1));
        assertThatThrownBy(() -> jdbc.update("""
                insert into checkout_attempts (id,user_id,idempotency_key,payload_hash,normalized_payload,status,
                  retry_count,version,created_at,updated_at)
                values (?,?,'constraint',?,'x','CREATED',0,0,current_timestamp,current_timestamp)
                """, UUID.randomUUID(),user,"0".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        UUID order=UUID.randomUUID();
        var stale=repository.findById(attempt.getId()).orElseThrow();
        attempt.setOrderId(order);
        attempt.setStatus(CheckoutAttempt.Status.RECOVERY_REQUIRED);
        attempt.setLastError("inventory timeout: reconcile same identity");
        attempt.setRetryCount(1);
        repository.saveAndFlush(attempt);
        var reloaded=repository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getOrderId()).isEqualTo(order);
        assertThat(reloaded.getLastError()).contains("reconcile");
        assertThat(reloaded.getVersion()).isGreaterThan(stale.getVersion());
        stale.setRetryCount(9);
        assertThatThrownBy(() -> repository.saveAndFlush(stale)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test void attemptCommitsEvenIfFutureOrchestratorTransactionRollsBack() {
        UUID user=UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            service.begin(user,"durable",request(UUID.randomUUID(),1));
            throw new IllegalStateException("remote failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByUserIdAndIdempotencyKey(user,"durable")).isPresent();
    }

    @Test void invalidIntentIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.begin(UUID.randomUUID(), " ", request(UUID.randomUUID(),1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.begin(UUID.randomUUID(), "key", request(UUID.randomUUID(),0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
