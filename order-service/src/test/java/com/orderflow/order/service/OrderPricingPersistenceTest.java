package com.orderflow.order.service;

import com.orderflow.order.client.*;
import com.orderflow.order.dto.request.*;
import com.orderflow.order.dto.response.ProductInfoResponse;
import com.orderflow.order.dto.inventory.response.*;
import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.producer.OrderEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class OrderPricingPersistenceTest {
    @Autowired OrderService service;
    @Autowired OrderRepository repository;
    @MockitoBean ProductClient products;
    @MockitoBean InventoryClient inventory;
    @MockitoBean OrderEventProducer events;

    @Test void persistedOrderKeepsServerValidatedPriceAfterCatalogPriceChanges() {
        UUID product=UUID.randomUUID(),user=UUID.randomUUID();
        var quote=ProductInfoResponse.builder().id(product).price(new BigDecimal("12.50")).build();
        when(products.getProduct(product)).thenReturn(quote);
        when(inventory.checkAvailability(any())).thenReturn(InventoryAvailabilityResponse.builder().available(true).build());
        when(inventory.reserveStock(any())).thenReturn(InventoryReservationResponse.builder().success(true).build());
        var response=service.createOrder(new CreateOrderRequest(List.of(new OrderItemRequest(product,3))),user);
        try {
            quote.setPrice(new BigDecimal("99.00"));
            var saved=repository.findWithOrderItemsById(response.getId()).orElseThrow();
            assertThat(saved.getTotalAmount()).isEqualByComparingTo("37.50");
            assertThat(saved.getOrderItems()).hasSize(1);
            assertThat(saved.getOrderItems().getFirst().getPrice()).isEqualByComparingTo("12.50");
            assertThat(saved.getOrderItems().getFirst().getQuantity()).isEqualTo(3);
            assertThat(saved.getUserId()).isEqualTo(user);
        } finally { repository.deleteById(response.getId()); }
    }
}
