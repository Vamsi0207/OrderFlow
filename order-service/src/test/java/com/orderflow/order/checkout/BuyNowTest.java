package com.orderflow.order.checkout;
import com.orderflow.order.client.ProductClient;
import com.orderflow.order.dto.response.ProductInfoResponse;
import com.orderflow.order.repository.OrderRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"eureka.client.enabled=false","spring.kafka.listener.auto-startup=false"})
class BuyNowTest {
    @Autowired BuyNowService service;
    @Autowired CheckoutWorkflowStore store;
    @Autowired CheckoutAttemptService attempts;
    @Autowired CheckoutAttemptRepository repository;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProductClient products;
    @MockitoBean CheckoutInventoryClient inventory;
    @MockitoBean CheckoutPaymentClient payments;
    BuyActor actor;UUID product;CheckoutRequest request;
    @BeforeEach void setup() {
        actor=new BuyActor(UUID.randomUUID(),"Bearer test-token");product=UUID.randomUUID();request=request(product,2);
        when(products.getProduct(any())).thenAnswer(call -> {
            assertNoTransaction();return ProductInfoResponse.builder().id(call.getArgument(0)).price(new BigDecimal("12.50")).stock(10).build();
        });
        when(inventory.reserve(any(),any())).thenAnswer(call -> {
            assertNoTransaction();return new CheckoutInventoryClient.Reservation(call.getArgument(0),"RESERVED",BuyNowService.quantities(call.getArgument(1)));
        });
        doAnswer(call -> {
            assertNoTransaction();UUID id=call.getArgument(0);var o=orders.findById(id).orElseThrow();
            return new CheckoutPaymentClient.Result(201,new CheckoutPaymentClient.Payment(UUID.randomUUID(),id,o.getUserId(),o.getTotalAmount(),o.getCurrency(),"PENDING","READY","test-only-client-secret"));
        }).when(payments).initialize(any(),any());
    }
    CheckoutRequest request(UUID id,int qty) {return new CheckoutRequest(List.of(new CheckoutRequest.Item(id,qty)));}
    void assertNoTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}

    @Test void singleAndMultiItemOrdersUseServerPricesAndPersistPendingScopedOrder() {
        var result=service.buy(actor,"single",request);assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().totalAmount()).isEqualByComparingTo("25.00");assertThat(result.body().currency()).isEqualTo("INR");
        assertThat(result.body().orderStatus()).isEqualTo("PENDING");assertThat(result.body().payment().clientSecret()).isNotBlank();
        var saved=orders.findWithOrderItemsById(result.body().orderId()).orElseThrow();
        assertThat(saved.isScopedReservation()).isTrue();assertThat(saved.isInventoryReserved()).isTrue();
        assertThat(saved.getCheckoutAttemptId()).isEqualTo(result.body().checkoutAttemptId());
        assertThat(saved.getOrderItems().getFirst().getPrice()).isEqualByComparingTo("12.50");
        var multi=service.buy(actor,"multi",new CheckoutRequest(List.of(new CheckoutRequest.Item(product,1),new CheckoutRequest.Item(UUID.randomUUID(),3))));
        assertThat(multi.body().totalAmount()).isEqualByComparingTo("50.00");assertThat(multi.body().items()).hasSize(2);
    }
    @Test void replayUsesSameOrderAndPayloadConflictIsRejected() {
        var first=service.buy(actor,"key",request);
        var p=first.body().payment();var response=new CheckoutPaymentClient.Payment(p.id(),first.body().orderId(),actor.userId(),first.body().totalAmount(),"INR","PENDING","READY","same-secret");
        doReturn(new CheckoutPaymentClient.Result(201,response)).when(payments).initialize(first.body().orderId(),actor);
        var replay=service.buy(actor,"key",request);assertThat(replay.body().orderId()).isEqualTo(first.body().orderId());
        assertThat(replay.body().payment().id()).isEqualTo(first.body().payment().id());
        verify(inventory,times(1)).reserve(any(),any());verify(products,times(1)).getProduct(product);
        assertThatThrownBy(() -> service.buy(actor,"key",request(product,3))).isInstanceOf(CheckoutConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from orders where checkout_attempt_id=?",Integer.class,first.body().checkoutAttemptId())).isEqualTo(1);
    }
    @Test void concurrentSameKeyHasOneWorkflowOwnerAndOneOrder() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(products.getProduct(product)).thenAnswer(call -> {entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return ProductInfoResponse.builder().id(product).price(BigDecimal.TEN).build();});
        try(var pool=Executors.newFixedThreadPool(6)) {
            var first=pool.submit(() -> service.buy(actor,"concurrent",request));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var futures=new ArrayList<Future<BuyNowService.Result>>();for(int i=0;i<5;i++)futures.add(pool.submit(() -> service.buy(actor,"concurrent",request)));
            for(var f:futures)assertThat(f.get(10,TimeUnit.SECONDS).status()).isEqualTo(202);
            release.countDown();var done=first.get(20,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from orders where checkout_attempt_id=?",Integer.class,done.body().checkoutAttemptId())).isEqualTo(1);
            verify(payments,times(1)).initialize(any(),any());verify(inventory,times(1)).reserve(any(),any());
        } finally {release.countDown();}
    }
    @Test void productFailureHasNoOrderOrRemoteSideEffectsAndCanResume() {
        doThrow(new IllegalStateException("down")).when(products).getProduct(product);
        var failed=service.buy(actor,"product-fail",request);assertThat(failed.status()).isEqualTo(503);assertThat(failed.body().orderId()).isNull();
        verifyNoInteractions(inventory,payments);
        doReturn(ProductInfoResponse.builder().id(product).price(BigDecimal.TEN).build()).when(products).getProduct(product);
        assertThat(service.buy(actor,"product-fail",request).status()).isEqualTo(201);
    }
    @Test void invalidProductAndInsufficientInventoryReturnClearErrors() {
        doThrow(WebClientResponseException.create(404,"missing",null,null,null)).when(products).getProduct(product);
        assertThat(service.buy(actor,"missing",request).status()).isEqualTo(404);
        doReturn(ProductInfoResponse.builder().id(product).price(BigDecimal.TEN).build()).when(products).getProduct(product);
        doThrow(WebClientResponseException.create(400,"insufficient",null,null,null)).when(inventory).reserve(any(),any());
        var insufficient=service.buy(actor,"insufficient",request);assertThat(insufficient.status()).isEqualTo(409);
        assertThat(insufficient.body().workflowStatus()).isEqualTo("ORDER_CREATED");verifyNoInteractions(payments);
    }
    @Test void reservationTimeoutResumesWithSameOrderAndNoPrematurePayment() {
        doThrow(new IllegalStateException("lost response")).when(inventory).reserve(any(),any());
        var first=service.buy(actor,"reserve-timeout",request);assertThat(first.status()).isEqualTo(503);verifyNoInteractions(payments);
        doAnswer(call -> new CheckoutInventoryClient.Reservation(call.getArgument(0),"RESERVED",BuyNowService.quantities(request))).when(inventory).reserve(any(),any());
        var retry=service.buy(actor,"reserve-timeout",request);assertThat(retry.body().orderId()).isEqualTo(first.body().orderId());assertThat(retry.status()).isEqualTo(201);
    }
    @Test void uncertainPaymentAnd202KeepReservationAndRetrySameOrder() {
        doThrow(new IllegalStateException("lost response")).when(payments).initialize(any(),any());
        var first=service.buy(actor,"pay-timeout",request);assertThat(first.status()).isEqualTo(202);
        assertThat(first.body().workflowStatus()).isEqualTo("INVENTORY_RESERVED");assertThat(first.body().orderStatus()).isEqualTo("PENDING");
        UUID payment=UUID.randomUUID();
        doReturn(new CheckoutPaymentClient.Result(202,new CheckoutPaymentClient.Payment(payment,first.body().orderId(),actor.userId(),first.body().totalAmount(),"INR","PENDING","UNKNOWN",null))).when(payments).initialize(any(),any());
        var second=service.buy(actor,"pay-timeout",request);assertThat(second.status()).isEqualTo(202);assertThat(second.body().payment().id()).isEqualTo(payment);
        verify(inventory,times(1)).reserve(any(),any());
    }
    @Test void reconciliation409IsDurableAndNotBlindlyReinitialized() {
        doAnswer(call -> {
            UUID order=call.getArgument(0);var o=orders.findById(order).orElseThrow();
            return new CheckoutPaymentClient.Result(409,new CheckoutPaymentClient.Payment(UUID.randomUUID(),order,actor.userId(),o.getTotalAmount(),"INR","PENDING","RECONCILIATION_REQUIRED",null));
        }).when(payments).initialize(any(),any());
        var first=service.buy(actor,"recon",request);assertThat(first.status()).isEqualTo(409);assertThat(first.body().workflowStatus()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(service.buy(actor,"recon",request).status()).isEqualTo(409);verify(payments,times(1)).initialize(any(),any());verify(inventory,times(1)).reserve(any(),any());
    }
    @Test void expiredLeaseAfterRestartIsFencedAndResumesPersistedOrder() {
        var a=attempts.begin(actor.userId(),"restart",request);UUID stale=UUID.randomUUID();assertThat(store.claim(a.getId(),stale)).isTrue();
        store.createOrder(a.getId(),stale,BuyNowService.quantities(request),Map.of(product,ProductInfoResponse.builder().id(product).price(BigDecimal.TEN).build()));
        UUID order=store.get(a.getId()).getOrderId();
        jdbc.update("update checkout_attempts set lease_until=current_timestamp-interval '1 second' where id=?",a.getId());
        var resumed=service.buy(actor,"restart",request);assertThat(resumed.body().orderId()).isEqualTo(order);
        assertThatThrownBy(() -> store.heartbeat(a.getId(),stale)).isInstanceOf(LeaseLostException.class);
        verifyNoInteractions(products);verify(inventory).reserve(eq(order),any());
    }
    @Test void failedOrderWriteRollsBackAssociationAndCanBeRetried() {
        var a=attempts.begin(actor.userId(),"write-fail",request);UUID owner=UUID.randomUUID();store.claim(a.getId(),owner);
        // An invalid numeric total fails before commit, so neither order nor association persists.
        assertThatThrownBy(() -> store.createOrder(a.getId(),owner,BuyNowService.quantities(request),Map.of(product,
                ProductInfoResponse.builder().id(product).price(new BigDecimal("99999999.99")).build())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get(a.getId()).getOrderId()).isNull();store.release(a.getId(),owner);
        assertThat(service.buy(actor,"write-fail",request).status()).isEqualTo(201);
    }
    @Test void databaseUniquenessFailureDoesNotStartRemoteOperationsAndCanRecover() {
        var a=attempts.begin(actor.userId(),"database-failure",request);
        var collision=com.orderflow.order.entity.Order.builder().checkoutAttemptId(a.getId()).scopedReservation(true)
                .userId(actor.userId()).status(com.orderflow.order.enums.OrderStatus.PENDING).totalAmount(BigDecimal.TEN).build();
        orders.saveAndFlush(collision);
        var failure=service.buy(actor,"database-failure",request);
        assertThat(failure.status()).isEqualTo(503);assertThat(store.get(a.getId()).getOrderId()).isNull();
        verifyNoInteractions(inventory,payments);orders.deleteById(collision.getId());
        assertThat(service.buy(actor,"database-failure",request).status()).isEqualTo(201);
    }
}
