package com.orderflow.inventory.reservation;

import com.orderflow.inventory.entity.Inventory;
import com.orderflow.inventory.repository.InventoryRepository;
import com.orderflow.inventory.service.InventoryService;
import com.orderflow.inventory.dto.request.ReserveStockRequest;
import com.orderflow.inventory.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "eureka.client.enabled=false")
class StockReservationTest {
    @Autowired StockReservationService service;
    @Autowired StockReservationRepository reservations;
    @Autowired InventoryRepository inventory;
    @Autowired InventoryService legacy;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    UUID stock(int quantity) {
        UUID id=UUID.randomUUID();
        inventory.saveAndFlush(Inventory.builder().productId(id).availableQuantity(quantity).build());
        return id;
    }
    ReservationRequest request(UUID id,int quantity) {
        return new ReservationRequest(List.of(new ReservationRequest.Item(id,quantity)));
    }
    void quantities(UUID id,int available,int reserved) {
        var row=inventory.findByProductId(id).orElseThrow();
        assertThat(row.getAvailableQuantity()).isEqualTo(available);
        assertThat(row.getReservedQuantity()).isEqualTo(reserved);
    }

    @Test void retryAfterLostResponseDoesNotReserveTwiceAndMismatchConflicts() {
        UUID product=stock(10), order=UUID.randomUUID();
        service.reserve(order,request(product,3)); // Discard response to simulate caller timeout after commit.
        assertThat(service.get(order).state()).isEqualTo(StockReservation.State.RESERVED);
        service.reserve(order,request(product,3));
        quantities(product,7,3);
        assertThatThrownBy(() -> service.reserve(order,request(product,4))).isInstanceOf(ReservationConflictException.class);
        quantities(product,7,3);
    }

    @Test void repeatedReleaseIsIdempotentAndTerminalReservationCannotReopen() {
        UUID product=stock(10), order=UUID.randomUUID();
        service.reserve(order,request(product,3));
        service.release(order); service.release(order);
        assertThat(service.reserve(order,request(product,3)).state()).isEqualTo(StockReservation.State.RELEASED);
        quantities(product,10,0);
        assertThatThrownBy(() -> service.deduct(order)).isInstanceOf(ReservationConflictException.class);
        quantities(product,10,0);
    }

    @Test void repeatedDeductionIsIdempotentAndReleaseCannotUndoSale() {
        UUID product=stock(10), order=UUID.randomUUID();
        service.reserve(order,request(product,3));
        service.deduct(order); service.deduct(order);
        quantities(product,7,0);
        assertThatThrownBy(() -> service.release(order)).isInstanceOf(ReservationConflictException.class);
        quantities(product,7,0);
    }

    @Test void unknownIdentityDoesNotReleaseSomeoneElsesStock() {
        UUID product=stock(10);
        service.reserve(UUID.randomUUID(),request(product,3));
        assertThatThrownBy(() -> service.release(UUID.randomUUID())).isInstanceOf(ReservationNotFoundException.class);
        quantities(product,7,3);
    }

    @Test void insufficientMultiItemReservationRollsBackHeaderLinesAndStock() {
        UUID a=stock(10), b=stock(0), order=UUID.randomUUID();
        // Ensure the successful row is mutated before the failure, proving rollback rather than precheck alone.
        UUID first=a.compareTo(b)<0?a:b, last=a.compareTo(b)<0?b:a;
        var firstRow=inventory.findByProductId(first).orElseThrow();firstRow.setAvailableQuantity(10);inventory.saveAndFlush(firstRow);
        var lastRow=inventory.findByProductId(last).orElseThrow();lastRow.setAvailableQuantity(0);inventory.saveAndFlush(lastRow);
        var batch=new ReservationRequest(List.of(new ReservationRequest.Item(last,1),new ReservationRequest.Item(first,2)));
        assertThatThrownBy(() -> service.reserve(order,batch)).isInstanceOf(InsufficientStockException.class);
        quantities(first,10,0);quantities(last,0,0);
        assertThat(reservations.findById(order)).isEmpty();
        lastRow.setAvailableQuantity(5);inventory.saveAndFlush(lastRow);
        service.reserve(order,batch);quantities(first,8,2);quantities(last,4,1);
        service.release(order);quantities(first,10,0);quantities(last,5,0);
    }

    @Test void missingProductRollsBackReservation() {
        UUID existing=stock(5), order=UUID.randomUUID();
        assertThatThrownBy(() -> service.reserve(order,new ReservationRequest(List.of(
                new ReservationRequest.Item(existing,1),new ReservationRequest.Item(UUID.randomUUID(),1)))))
                .isInstanceOf(InventoryNotFoundException.class);
        assertThat(reservations.findById(order)).isEmpty();quantities(existing,5,0);
    }

    @Test void simultaneousPurchasesCannotOversell() throws Exception {
        UUID product=stock(5);
        var results=race(12,i -> {
            try {service.reserve(UUID.randomUUID(),request(product,1));return true;}
            catch(InsufficientStockException expected){return false;}
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        quantities(product,0,5);
    }

    @Test void simultaneousDuplicateRequestsCreateOneReservation() throws Exception {
        UUID product=stock(10),order=UUID.randomUUID();
        race(8,i -> service.reserve(order,request(product,3)));
        quantities(product,7,3);
        race(8,i -> service.release(order));quantities(product,10,0);
    }

    @Test void simultaneousDeductionAndReleaseHaveOnlyOneTerminalWinner() throws Exception {
        UUID product=stock(10),order=UUID.randomUUID();
        service.reserve(order,request(product,3));
        var results=race(2,i -> {
            try {if(i==0)service.release(order);else service.deduct(order);return true;}
            catch(ReservationConflictException expected){return false;}
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        quantities(product,service.get(order).state()==StockReservation.State.RELEASED?10:7,0);
    }

    @Test void oppositeInputOrderUsesConsistentLocksAndSplitLinesAreNormalized() throws Exception {
        UUID a=stock(10),b=stock(10);
        race(2,i -> service.reserve(UUID.randomUUID(),new ReservationRequest(i==0
                ?List.of(new ReservationRequest.Item(a,1),new ReservationRequest.Item(b,1))
                :List.of(new ReservationRequest.Item(b,1),new ReservationRequest.Item(a,1)))));
        quantities(a,8,2);quantities(b,8,2);
        UUID order=UUID.randomUUID();
        service.reserve(order,new ReservationRequest(List.of(new ReservationRequest.Item(a,1),new ReservationRequest.Item(a,1))));
        service.reserve(order,request(a,2));quantities(a,6,4);
    }

    @Test void legacyCannotConsumeScopedReservationsButCanUseItsOwn() {
        UUID product=stock(10),order=UUID.randomUUID();
        service.reserve(order,request(product,3));
        var one=ReserveStockRequest.builder().productId(product).quantity(1).build();
        assertThatThrownBy(() -> legacy.releaseStock(one)).isInstanceOf(InsufficientReservedStockException.class);
        assertThatThrownBy(() -> legacy.deductStock(one)).isInstanceOf(InsufficientReservedStockException.class);
        assertThatThrownBy(() -> legacy.deleteInventory(product)).isInstanceOf(ReservationConflictException.class);
        legacy.reserveStock(one);legacy.deductStock(one);quantities(product,6,3);
        service.release(order);quantities(product,9,0);
        legacy.reserveStock(one);legacy.releaseStock(one);quantities(product,9,0);
    }

    @Test void legacyAndScopedConcurrentPurchasesShareTheSameProductLock() throws Exception {
        UUID product=stock(3);
        var one=ReserveStockRequest.builder().productId(product).quantity(1).build();
        var results=race(10,i -> {
            try { if(i%2==0)legacy.reserveStock(one);else service.reserve(UUID.randomUUID(),request(product,1));return true; }
            catch(InsufficientStockException expected){return false;}
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);quantities(product,0,3);
    }


    @Test void simultaneousConflictingPayloadsHaveOneWinner() throws Exception {
        UUID product=stock(10),order=UUID.randomUUID();
        var results=race(2,i -> {
            try {service.reserve(order,request(product,i+1));return true;}
            catch(ReservationConflictException expected){return false;}
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        int reserved=service.get(order).items().get(product);
        quantities(product,10-reserved,reserved);
    }

    @Test void simultaneousRepeatedDeductionsHaveOneStockEffect() throws Exception {
        UUID product=stock(10),order=UUID.randomUUID();
        service.reserve(order,request(product,3));
        race(8,i -> service.deduct(order));quantities(product,7,0);
    }


    @Test void legacyCannotConsumeUnflushedScopedReservationInSharedTransaction() {
        UUID product=stock(10),order=UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.reserve(order,request(product,3));
            var one=ReserveStockRequest.builder().productId(product).quantity(1).build();
            // Use the guard's failure to roll back the shared transaction, just as any stock mutation failure.
            assertThatThrownBy(() -> legacy.deductStock(one)).isInstanceOf(InsufficientReservedStockException.class);
            status.setRollbackOnly();
        });
        quantities(product,10,0);
        assertThat(reservations.findById(order)).isEmpty();
    }

    private <T> List<T> race(int count, ThrowingFunction<T> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);var ready=new CountDownLatch(count);
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++){final int index=i;futures.add(pool.submit(() -> {
                ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("start timeout");
                return action.apply(index);
            }));}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new ArrayList<T>();for(var f:futures)results.add(f.get(30,TimeUnit.SECONDS));return results;
        }
    }
    interface ThrowingFunction<T> { T apply(int index) throws Exception; }
}
