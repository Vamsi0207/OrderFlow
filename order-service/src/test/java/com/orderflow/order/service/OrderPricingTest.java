package com.orderflow.order.service;

import com.orderflow.order.client.*;
import com.orderflow.order.dto.request.*;
import com.orderflow.order.dto.response.ProductInfoResponse;
import com.orderflow.order.dto.inventory.response.*;
import com.orderflow.order.entity.Order;
import com.orderflow.order.repository.*;
import com.orderflow.order.producer.OrderEventProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class OrderPricingTest {
    @Mock OrderRepository orders;
    @Mock OrderItemRepository items;
    @Mock ProductClient products;
    @Mock InventoryClient inventory;
    @Mock OrderEventProducer events;
    @InjectMocks OrderService service;

    @Test void computesServerTotalAndPersistsPriceSnapshots() {
        UUID product=UUID.randomUUID(),user=UUID.randomUUID();
        when(products.getProduct(product)).thenReturn(ProductInfoResponse.builder().id(product).price(new BigDecimal("12.50")).build());
        when(inventory.checkAvailability(any())).thenReturn(InventoryAvailabilityResponse.builder().available(true).build());
        when(inventory.reserveStock(any())).thenReturn(InventoryReservationResponse.builder().success(true).build());
        when(orders.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var response=service.createOrder(CreateOrderRequest.builder().items(List.of(
                OrderItemRequest.builder().productId(product).quantity(3).build())).build(),user);
        assertThat(response.getTotalAmount()).isEqualByComparingTo("37.50");
        var captor=ArgumentCaptor.forClass(Order.class);verify(orders).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOrderItems().getFirst().getPrice()).isEqualByComparingTo("12.50");
        assertThat(captor.getValue().getUserId()).isEqualTo(user);
        verify(events).publishOrderCreated(any());
    }

    @Test void validatesAllPricesBeforeAnyRemoteReservation() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        when(products.getProduct(a)).thenReturn(ProductInfoResponse.builder().id(a).price(new BigDecimal("10.00")).build());
        when(products.getProduct(b)).thenReturn(ProductInfoResponse.builder().id(b).price(BigDecimal.ZERO).build());
        var request=new CreateOrderRequest(List.of(new OrderItemRequest(a,1),new OrderItemRequest(b,1)));
        assertThatThrownBy(() -> service.createOrder(request,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory,orders,events);
    }

    @Test void mismatchedProductIdentityIsRejected() {
        UUID id=UUID.randomUUID();
        when(products.getProduct(id)).thenReturn(ProductInfoResponse.builder().id(UUID.randomUUID()).price(BigDecimal.TEN).build());
        assertThatThrownBy(() -> service.createOrder(new CreateOrderRequest(List.of(new OrderItemRequest(id,1))),UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory,orders,events);
    }
}
