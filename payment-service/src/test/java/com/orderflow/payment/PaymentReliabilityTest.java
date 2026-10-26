package com.orderflow.payment;

import com.orderflow.payment.client.*;
import com.orderflow.payment.security.PaymentActor;
import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.enums.*;
import com.orderflow.payment.repository.PaymentRepository;
import com.orderflow.payment.service.*;
import com.orderflow.payment.producer.PaymentEventProducer;
import com.stripe.model.*;
import com.stripe.net.ApiResource;
import com.stripe.exception.ApiConnectionException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"eureka.client.enabled=false"})
class PaymentReliabilityTest {
    @Autowired PaymentService service;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired PaymentStore store;
    @Autowired PaymentInitializer initializer;
    @Autowired PaymentRepository repository;
    @Autowired PaymentWebhookService webhooks;
    @Autowired PaymentRecoveryWorker recoveryWorker;
    @Autowired PaymentRecoveryStore recovery;
    @MockitoBean OrderClient orders;
    @MockitoSpyBean StripePaymentService stripe;
    @MockitoBean PaymentEventProducer producer;
    PaymentActor actor;
    AuthoritativeOrder order;

    @BeforeEach void fixtures() {
        actor=new PaymentActor(UUID.randomUUID(),"USER","Bearer test-user-token");
        order=new AuthoritativeOrder(UUID.randomUUID(),actor.userId(),"PENDING",new BigDecimal("12.50"),"USD");
        when(orders.get(order.id(),actor)).thenReturn(order);
    }
    CreatePaymentRequest request() {return CreatePaymentRequest.builder().orderId(order.id()).amount(new BigDecimal("0.01")).build();}
    PaymentIntent intent(Payment p,String status) {
        var intent=new PaymentIntent();intent.setId("pi_"+p.getId().toString().replace("-",""));
        intent.setAmount(1250L);intent.setAmountReceived("succeeded".equals(status)?1250L:0L);intent.setCurrency("usd");
        intent.setMetadata(Map.of("orderId",p.getOrderId().toString(),"paymentId",p.getId().toString(),"userId",p.getUserId().toString()));
        intent.setStatus(status);intent.setClientSecret("test-only-client-secret");return intent;
    }
    Payment ready() throws Exception {
        doAnswer(call -> intent(call.getArgument(0),"requires_payment_method")).when(stripe).create(any());
        var response=service.createPayment(request(),actor);
        return repository.findById(response.getId()).orElseThrow();
    }
    Event event(PaymentIntent intent,String type) {
        String payload="{\"id\":\"evt_test\",\"object\":\"event\",\"type\":\""+type+"\",\"data\":{\"object\":{\"object\":\"payment_intent\",\"id\":\""+intent.getId()+"\"}}}";
        return ApiResource.GSON.fromJson(payload,Event.class);
    }
    void deliver(Payment p,String status,String type) throws Exception {
        var intent=intent(p,status);doReturn(intent).when(stripe).retrieve(intent.getId());webhooks.handleEvent(event(intent,type));
    }

    @Test void ignoresClientAmountAndUsesPersistedOrderCurrency() throws Exception {
        doAnswer(call -> {
            Payment p=call.getArgument(0);
            // Intent is committed and visible through another transaction before remote creation.
            try(var connection=dataSource.getConnection();var statement=connection.prepareStatement("select count(*) from payments where id=?")) {
                statement.setObject(1,p.getId());try(var result=statement.executeQuery()){result.next();assertThat(result.getInt(1)).isEqualTo(1);}
            }
            assertThat(p.getAmount()).isEqualByComparingTo("12.50");assertThat(p.getCurrency()).isEqualTo("USD");
            return intent(p,"requires_payment_method");
        }).when(stripe).create(any());
        var response=service.createPayment(request(),actor);
        assertThat(response.getAmount()).isEqualByComparingTo("12.50");assertThat(response.getCurrency()).isEqualTo("USD");
        assertThat(response.getClientSecret()).isNotBlank();assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(producer);
    }

    @Test void rejectsOtherOwnersIncludingAdminsBeforeStripeOrPaymentCreation() {
        var another=new AuthoritativeOrder(order.id(),UUID.randomUUID(),"PENDING",order.totalAmount(),order.currency());
        when(orders.get(order.id(),actor)).thenReturn(another);
        assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(repository.findByOrderId(order.id())).isEmpty();
        verifyNoInteractions(stripe);
    }

    @Test void rejectsMissingAndIneligibleOrders() {
        when(orders.get(order.id(),actor)).thenReturn(null);
        assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOf(ResponseStatusException.class);
        for(String status:List.of("CANCELLED","CONFIRMED","DELIVERED")) {
            when(orders.get(order.id(),actor)).thenReturn(new AuthoritativeOrder(order.id(),actor.userId(),status,order.totalAmount(),order.currency()));
            assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        }
        verifyNoInteractions(stripe);
    }

    @Test void rejectsInvalidOrderAmountAndUnsupportedCurrency() {
        for(AuthoritativeOrder bad:List.of(
                new AuthoritativeOrder(order.id(),actor.userId(),"PENDING",BigDecimal.ZERO,"INR"),
                new AuthoritativeOrder(order.id(),actor.userId(),"PENDING",new BigDecimal("1.001"),"INR"),
                new AuthoritativeOrder(order.id(),actor.userId(),"PENDING",BigDecimal.TEN,"JPY"))) {
            when(orders.get(order.id(),actor)).thenReturn(bad);
            assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(stripe);
    }

    @Test void duplicateInitializationRetrievesExistingIntentAndSecret() throws Exception {
        var p=ready();doReturn(intent(p,"requires_payment_method")).when(stripe).retrieve(p.getStripePaymentIntentId());
        var response=service.createPayment(request(),actor);
        assertThat(response.getId()).isEqualTo(p.getId());assertThat(response.getClientSecret()).isNotBlank();
        verify(stripe,times(1)).create(any());
    }

    @Test void concurrentInitializationHasOneDurablePaymentAndOneCreateCall() throws Exception {
        doAnswer(call -> intent(call.getArgument(0),"requires_payment_method")).when(stripe).create(any());
        doAnswer(call -> {
            var p=repository.findByOrderId(order.id()).orElseThrow();return intent(p,"requires_payment_method");
        }).when(stripe).retrieve(anyString());
        var results=race(8,() -> service.createPayment(request(),actor).getId());
        assertThat(new HashSet<>(results)).hasSize(1);
        verify(stripe,times(1)).create(any());
        assertThat(repository.findByOrderId(order.id())).isPresent();
    }

    @Test void timeoutRemainsUnresolvedAndRetryUsesSameAttemptAndKey() throws Exception {
        var keys=new ArrayList<String>();
        doAnswer(call -> {
            Payment p=call.getArgument(0);keys.add(stripe.idempotencyKey(p));
            if(keys.size()==1)throw new ApiConnectionException("simulated timeout");
            return intent(p,"requires_payment_method");
        }).when(stripe).create(any());
        var first=service.createPayment(request(),actor);
        assertThat(first.getInitializationState()).isEqualTo(InitializationState.UNKNOWN);assertThat(first.getClientSecret()).isNull();
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING);
        var retry=service.createPayment(request(),actor);
        assertThat(retry.getId()).isEqualTo(first.getId());assertThat(retry.getInitializationState()).isEqualTo(InitializationState.READY);
        assertThat(keys).hasSize(2);assertThat(keys.getFirst()).isEqualTo(keys.getLast());verifyNoInteractions(producer);
    }

    @Test void unresolvedOldAttemptIsNeverRecreatedAfterRetentionWindow() throws Exception {
        var p=store.prepare(order);p.setInitializationStartedAt(Instant.now().minus(Duration.ofHours(25)));repository.saveAndFlush(p);
        var response=service.createPayment(request(),actor);
        assertThat(response.getInitializationState()).isEqualTo(InitializationState.RECONCILIATION_REQUIRED);
        assertThat(response.getClientSecret()).isNull();verifyNoInteractions(stripe,producer);
    }

    @Test void webhookRecoversIntentAfterAmbiguousCreateBeforeIdWasSaved() throws Exception {
        doThrow(new ApiConnectionException("lost response")).when(stripe).create(any());
        var first=service.createPayment(request(),actor);
        var p=repository.findById(first.getId()).orElseThrow();assertThat(p.getStripePaymentIntentId()).isNull();
        deliver(p,"succeeded","payment_intent.succeeded");
        var saved=repository.findById(p.getId()).orElseThrow();
        assertThat(saved.getStripePaymentIntentId()).isEqualTo(intent(p,"succeeded").getId());
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(producer).publishPaymentCompleted(any());
    }

    @Test void repeatedAndConcurrentSuccessWebhooksPublishOneNotification() throws Exception {
        var p=ready();var current=intent(p,"succeeded");doReturn(current).when(stripe).retrieve(current.getId());
        race(8,() -> {webhooks.handleEvent(event(current,"payment_intent.succeeded"));return true;});
        assertThat(repository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(producer,times(1)).publishPaymentCompleted(any());
    }

    @Test void retryableCardFailureAndProcessingDoNotReleaseInventoryOrFailPayment() throws Exception {
        var p=ready();deliver(p,"requires_payment_method","payment_intent.payment_failed");
        deliver(p,"processing","payment_intent.processing");
        assertThat(repository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(producer);
    }

    @Test void completedPaymentNeverRegressesAndCanceledIsDefinitiveFailure() throws Exception {
        var p=ready();deliver(p,"succeeded","payment_intent.succeeded");
        deliver(p,"requires_payment_method","payment_intent.payment_failed");deliver(p,"canceled","payment_intent.canceled");
        assertThat(repository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(producer,times(1)).publishPaymentCompleted(any());verify(producer,never()).publishPaymentFailed(any());
    }

    @Test void canceledPaymentCannotBeResurrectedByALateSuccessEvent() throws Exception {
        var p=ready();deliver(p,"canceled","payment_intent.canceled");deliver(p,"succeeded","payment_intent.succeeded");
        assertThat(repository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(producer,times(1)).publishPaymentFailed(any());verify(producer,never()).publishPaymentCompleted(any());
        assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void notificationFailureLeavesDurablePendingStateAndRedeliveryRepairsIt() throws Exception {
        var p=ready();doThrow(new IllegalStateException("broker down")).doNothing().when(producer).publishPaymentCompleted(any());
        assertThatThrownBy(() -> deliver(p,"succeeded","payment_intent.succeeded")).isInstanceOf(IllegalStateException.class);
        var pending=repository.findById(p.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.COMPLETED);assertThat(pending.isEventPublished()).isFalse();
        deliver(p,"succeeded","payment_intent.succeeded");assertThat(repository.findById(p.getId()).orElseThrow().isEventPublished()).isTrue();
        verify(producer,times(2)).publishPaymentCompleted(any());
    }

    @Test void snapshotMismatchAndUnderpaymentNeverCompleteAPayment() throws Exception {
        var p=ready();var bad=intent(p,"succeeded");bad.setAmountReceived(100L);
        doReturn(bad).when(stripe).retrieve(bad.getId());
        assertThatThrownBy(() -> webhooks.handleEvent(event(bad,"payment_intent.succeeded"))).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        bad.setAmountReceived(1250L);bad.setCurrency("eur");
        assertThatThrownBy(() -> webhooks.handleEvent(event(bad,"payment_intent.succeeded"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(producer);
    }

    @Test void cannotReadOtherUsersPaymentOrListTheirPayments() throws Exception {
        var p=ready();var other=new PaymentActor(UUID.randomUUID(),"USER","Bearer other");
        assertThatThrownBy(() -> service.getPaymentById(p.getId(),other)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.getPaymentsByUser(actor.userId(),other)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.getAllPayments(other)).isEmpty();
    }


    @Test void knownIntentRetrievalFailureNeverStartsAnotherIntent() throws Exception {
        var p=ready();doThrow(new ApiConnectionException("timeout")).when(stripe).retrieve(p.getStripePaymentIntentId());
        var response=service.createPayment(request(),actor);
        assertThat(response.getInitializationState()).isEqualTo(InitializationState.UNKNOWN);
        assertThat(response.getClientSecret()).isNull();verify(stripe,times(1)).create(any());
        assertThat(repository.findById(p.getId()).orElseThrow().getStripePaymentIntentId()).isEqualTo(p.getStripePaymentIntentId());
    }

    @Test void mismatchedInitializationResultRequiresReconciliationWithoutSecret() throws Exception {
        doAnswer(call -> {var result=intent(call.getArgument(0),"requires_payment_method");result.setAmount(1L);return result;}).when(stripe).create(any());
        var response=service.createPayment(request(),actor);
        assertThat(response.getInitializationState()).isEqualTo(InitializationState.RECONCILIATION_REQUIRED);
        assertThat(response.getClientSecret()).isNull();assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test void missingClientSecretIsUnresolvedButKnownIntentIdIsKept() throws Exception {
        doAnswer(call -> {var result=intent(call.getArgument(0),"requires_payment_method");result.setClientSecret(null);return result;}).when(stripe).create(any());
        var response=service.createPayment(request(),actor);
        assertThat(response.getInitializationState()).isEqualTo(InitializationState.UNKNOWN);assertThat(response.getClientSecret()).isNull();
        assertThat(repository.findById(response.getId()).orElseThrow().getStripePaymentIntentId()).isNotBlank();
    }

    @Test void legacyTerminalRowsAreNotProofWithoutStripeCorroboration() throws Exception {
        var p=ready();p.setStatus(PaymentStatus.FAILED);p.setTerminalVerified(false);repository.saveAndFlush(p);
        assertThat(new com.orderflow.payment.controller.InternalPaymentController(repository).status(p.getId()).status()).isEqualTo(PaymentStatus.PENDING);
        deliver(p,"requires_payment_method","payment_intent.payment_failed");
        assertThat(repository.findById(p.getId()).orElseThrow().verifiedStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(producer);
    }

    @Test void legacyCompletedOutcomeCanBeCorroboratedByCurrentStripeSuccess() throws Exception {
        var p=ready();p.setStatus(PaymentStatus.COMPLETED);p.setTerminalVerified(false);repository.saveAndFlush(p);
        deliver(p,"succeeded","payment_intent.succeeded");
        assertThat(repository.findById(p.getId()).orElseThrow().isTerminalVerified()).isTrue();verify(producer).publishPaymentCompleted(any());
    }

    private <T> List<T> race(int count,Callable<T> call) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);var ready=new CountDownLatch(count);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++)futures.add(pool.submit(() -> {ready.countDown();start.await();return call.call();}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new ArrayList<T>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
        }
    }

    @Test void scheduledOutboxRepairDoesNotNeedAnotherWebhook() throws Exception {
        var p=ready();
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(producer).publishPaymentCompleted(any());
        assertThatThrownBy(() -> deliver(p,"succeeded","payment_intent.succeeded")).isInstanceOf(IllegalStateException.class);
        assertThat(recovery.due()).contains(p.getId());
        recoveryWorker.recover(p.getId());
        var saved=repository.findById(p.getId()).orElseThrow();
        assertThat(saved.isEventPublished()).isTrue();assertThat(saved.getRecoveryError()).isNull();
        recoveryWorker.recover(p.getId());verify(producer,times(2)).publishPaymentCompleted(any());
    }
    @Test void missedWebhookReconcilesKnownIntentAndPublishesVerifiedOutcome() throws Exception {
        var p=ready();doReturn(intent(p,"succeeded")).when(stripe).retrieve(p.getStripePaymentIntentId());
        recoveryWorker.recover(p.getId());
        var saved=repository.findById(p.getId()).orElseThrow();
        assertThat(saved.verifiedStatus()).isEqualTo(PaymentStatus.COMPLETED);assertThat(saved.isEventPublished()).isTrue();
        verify(producer).publishPaymentCompleted(any());verify(stripe,times(1)).create(any());
    }
    @Test void unresolvedIntentRemainsPendingWithoutFailureEventOrNewCreation() throws Exception {
        var p=ready();doReturn(intent(p,"processing")).when(stripe).retrieve(p.getStripePaymentIntentId());
        recoveryWorker.recover(p.getId());recoveryWorker.recover(p.getId());
        var saved=repository.findById(p.getId()).orElseThrow();
        assertThat(saved.verifiedStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getRecoveryNextAt()).isAfter(Instant.now());verifyNoInteractions(producer);
        verify(stripe,times(1)).retrieve(p.getStripePaymentIntentId());verify(stripe,times(1)).create(any());
    }
    @Test void recoveryFailureBacksOffAndStopsAtDurableBudget() throws Exception {
        var p=ready();p.setRecoveryAttempts(9);repository.saveAndFlush(p);
        doThrow(new ApiConnectionException("sensitive SDK diagnostic must not be persisted")).when(stripe).retrieve(p.getStripePaymentIntentId());
        recoveryWorker.recover(p.getId());recoveryWorker.recover(p.getId());
        var saved=repository.findById(p.getId()).orElseThrow();
        assertThat(saved.getRecoveryAttempts()).isEqualTo(10);assertThat(saved.getRecoveryError()).isEqualTo("RECOVERY_EXHAUSTED");
        assertThat(saved.getRecoveryNextAt()).isAfter(Instant.now());assertThat(recovery.due()).doesNotContain(p.getId());
        assertThat(saved.verifiedStatus()).isEqualTo(PaymentStatus.PENDING);verifyNoInteractions(producer);
        verify(stripe,times(1)).retrieve(p.getStripePaymentIntentId());
    }
    @Test void crashedRecoveryClaimExpiresAndOldWorkerCannotOverwriteNewProgress() throws Exception {
        var p=ready();var old=recovery.claim(p.getId());assertThat(old).isNotNull();assertThat(recovery.claim(p.getId())).isNull();
        p=repository.findById(p.getId()).orElseThrow();p.setRecoveryNextAt(Instant.now().minusSeconds(1));repository.saveAndFlush(p);
        var replacement=recovery.claim(p.getId());assertThat(replacement.token()).isNotEqualTo(old.token());
        recovery.succeeded(p.getId(),replacement.token());recovery.failed(p.getId(),old.token());
        var saved=repository.findById(p.getId()).orElseThrow();assertThat(saved.getRecoveryAttempts()).isZero();assertThat(saved.getRecoveryError()).isNull();
    }
    @Test void noIntentIdRecoveryNeverCreatesAnotherLogicalAttempt() {
        var p=store.prepare(order);recoveryWorker.recover(p.getId());
        assertThat(recovery.claim(p.getId())).isNull();verifyNoInteractions(stripe,producer);
    }
    @Test void unreservedBuyOrderIsIneligibleForDirectPaymentInitialization() {
        when(orders.get(order.id(),actor)).thenReturn(new AuthoritativeOrder(order.id(),actor.userId(),"PENDING",order.totalAmount(),order.currency(),false));
        assertThatThrownBy(() -> service.createPayment(request(),actor)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
        assertThat(repository.findByOrderId(order.id())).isEmpty();verifyNoInteractions(stripe,producer);
    }

    @Test void terminalOutcomeRestartsExhaustedReadBudgetForNewOutboxWork() throws Exception {
        var p=ready();p.setRecoveryAttempts(10);p.setRecoveryError("RECOVERY_EXHAUSTED");repository.saveAndFlush(p);
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(producer).publishPaymentCompleted(any());
        assertThatThrownBy(() -> deliver(p,"succeeded","payment_intent.succeeded")).isInstanceOf(IllegalStateException.class);
        var pending=repository.findById(p.getId()).orElseThrow();
        assertThat(pending.getRecoveryAttempts()).isZero();assertThat(pending.isEventPublished()).isFalse();
        recoveryWorker.recover(p.getId());
        assertThat(repository.findById(p.getId()).orElseThrow().isEventPublished()).isTrue();
    }
}
