package com.orderflow.order.consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.client.*;
import com.orderflow.order.entity.*;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {
    @Mock OrderRepository orders;
    @Mock InventoryClient inventory;
    @Mock PaymentStatusClient payments;
    @Mock com.orderflow.order.service.LegacyPaymentEffectStore effects;
    Order order;
    UUID paymentId;
    String message;
    PaymentEventConsumer consumer;
    @BeforeEach void setup() throws Exception {
        consumer=new PaymentEventConsumer(orders,inventory,new ObjectMapper(),payments,effects);
        order=Order.builder().userId(UUID.randomUUID()).status(OrderStatus.PENDING).totalAmount(new BigDecimal("12.50")).build();
        ReflectionTestUtils.setField(order,"id",UUID.randomUUID());
        order.getOrderItems().add(OrderItem.builder().productId(UUID.randomUUID()).quantity(2).price(new BigDecimal("6.25")).order(order).build());
        paymentId=UUID.randomUUID();
        message=new ObjectMapper().writeValueAsString(new PaymentEventConsumer.PaymentEvent(paymentId,order.getId(),order.getUserId(),order.getTotalAmount()));
        lenient().when(effects.claim(any(),any(),any(),anyInt(),anyBoolean())).thenReturn(true);
        when(orders.findLocked(order.getId())).thenReturn(Optional.of(order));
    }
    PaymentStatusClient.Status status(String status) {return new PaymentStatusClient.Status(paymentId,order.getId(),order.getUserId(),order.getTotalAmount(),"INR",status);}
    @Test void onlyVerifiedSuccessConfirmsAndDuplicateDeliveryDoesNotDeductAgain() {
        when(payments.get(paymentId)).thenReturn(status("COMPLETED"));
        consumer.consumePaymentCompleted(message);consumer.consumePaymentCompleted(message);consumer.consumePaymentFailed(message);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);verify(inventory,times(1)).deductStock(any());verify(inventory,never()).releaseStock(any());
    }
    @Test void pendingUnknownAndRetryableFailuresNeverReleaseOrConfirm() {
        when(payments.get(paymentId)).thenReturn(status("PENDING"));
        consumer.consumePaymentFailed(message);consumer.consumePaymentCompleted(message);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);verifyNoInteractions(inventory);verify(orders,never()).save(any());
    }
    @Test void definitiveFailureReleasesOnceAndNeverResurrectsCancelledOrder() {
        when(payments.get(paymentId)).thenReturn(status("FAILED"));
        consumer.consumePaymentFailed(message);consumer.consumePaymentFailed(message);consumer.consumePaymentCompleted(message);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);verify(inventory,times(1)).releaseStock(any());verify(inventory,never()).deductStock(any());
    }
    @Test void staleFailureTopicDoesNotCancelVerifiedCompletedPayment() {
        when(payments.get(paymentId)).thenReturn(status("COMPLETED"));
        consumer.consumePaymentFailed(message);assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        consumer.consumePaymentCompleted(message);assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);verify(inventory,never()).releaseStock(any());
    }
    @Test void deliveredOrderCannotBeCancelledOrConfirmedAgain() {
        order.setStatus(OrderStatus.DELIVERED);consumer.consumePaymentFailed(message);consumer.consumePaymentCompleted(message);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);verifyNoInteractions(payments,inventory);
    }
    @Test void forgedOrMismatchedPaymentDataCannotChangeOrderOrStock() {
        when(payments.get(paymentId)).thenReturn(new PaymentStatusClient.Status(paymentId,order.getId(),UUID.randomUUID(),order.getTotalAmount(),"INR","COMPLETED"));
        assertThatThrownBy(() -> consumer.consumePaymentCompleted(message)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory);assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
    @Test void unavailablePaymentValidationIsRetryableAndDoesNotReleaseStock() {
        when(payments.get(paymentId)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> consumer.consumePaymentFailed(message)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(inventory);
    }
    @Test void scopedReservationsUseIdempotentLifecycleInsteadOfLegacyCalls() {
        when(payments.get(paymentId)).thenReturn(status("COMPLETED"));when(inventory.hasScopedReservation(order.getId())).thenReturn(true);
        consumer.consumePaymentCompleted(message);verify(inventory).finishScoped(order.getId(),true);
        verify(inventory,never()).deductStock(any());verify(inventory,never()).releaseStock(any());
    }
    @Test void inventoryFailurePropagatesForRetryInsteadOfBeingSwallowed() {
        when(payments.get(paymentId)).thenReturn(status("COMPLETED"));
        when(inventory.deductStock(any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> consumer.consumePaymentCompleted(message)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);verify(orders,never()).save(any());
    }
}
