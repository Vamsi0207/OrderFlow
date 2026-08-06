package com.orderflow.order.service;

import com.orderflow.order.client.ProductClient;
import com.orderflow.order.dto.request.CreateOrderRequest;
import com.orderflow.order.dto.request.OrderItemRequest;
import com.orderflow.order.dto.response.OrderItemResponse;
import com.orderflow.order.dto.response.OrderResponse;
import com.orderflow.order.entity.Order;
import com.orderflow.order.entity.OrderItem;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.exception.OrderNotFoundException;
import com.orderflow.order.repository.OrderItemRepository;
import com.orderflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orderflow.order.dto.response.ProductInfoResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductClient productClient;

    public OrderResponse createOrder(CreateOrderRequest request) {

    Order order = Order.builder()
            .userId(request.getUserId())
            .status(OrderStatus.PENDING)
            .totalAmount(BigDecimal.ZERO)
            .build();

    List<OrderItem> orderItems = new ArrayList<>();

    BigDecimal totalAmount = BigDecimal.ZERO;

    for (OrderItemRequest itemRequest : request.getItems()) {

        ProductInfoResponse product =
        productClient.getProduct(itemRequest.getProductId());

        BigDecimal price = product.getPrice();

        OrderItem orderItem = OrderItem.builder()
                .productId(itemRequest.getProductId())
                .quantity(itemRequest.getQuantity())
                .price(price)
                .order(order)
                .build();

        orderItems.add(orderItem);

        totalAmount = totalAmount.add(
                price.multiply(
                        BigDecimal.valueOf(itemRequest.getQuantity())
                )
        );
    }

    order.setOrderItems(orderItems);
    order.setTotalAmount(totalAmount);

    Order savedOrder = orderRepository.save(order);

    return mapToResponse(savedOrder);
 }
 @Transactional(readOnly = true)
 public List<OrderResponse> getAllOrders() {

    return orderRepository.findAll()
            .stream()
            .map(this::mapToResponse)
            .toList();
 }
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID id) {

    Order order = orderRepository.findById(id)
            .orElseThrow(() ->
                    new OrderNotFoundException(id.toString()));

    return mapToResponse(order);
 }
 @Transactional(readOnly = true)
 public List<OrderResponse> getOrdersByUser(UUID userId) {

    return orderRepository.findByUserId(userId)
            .stream()
            .map(this::mapToResponse)
            .toList();
}

    private OrderResponse mapToResponse(Order order) {

    return OrderResponse.builder()
            .id(order.getId())
            .userId(order.getUserId())
            .status(order.getStatus())
            .totalAmount(order.getTotalAmount())
            .items(
                    order.getOrderItems()
                            .stream()
                            .map(this::mapItemToResponse)
                            .toList()
            )
            .createdAt(order.getCreatedAt())
            .build();
}

private OrderItemResponse mapItemToResponse(OrderItem orderItem) {

    return OrderItemResponse.builder()
            .productId(orderItem.getProductId())
            .quantity(orderItem.getQuantity())
            .price(orderItem.getPrice())
            .build();
}

}