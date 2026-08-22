package com.orderflow.order.service;

import com.orderflow.order.client.InventoryClient;
import com.orderflow.order.client.ProductClient;
import com.orderflow.order.dto.inventory.request.InventoryAvailabilityRequest;
import com.orderflow.order.dto.inventory.request.InventoryReservationRequest;
import com.orderflow.order.dto.inventory.response.InventoryAvailabilityResponse;
import com.orderflow.order.dto.inventory.response.InventoryReservationResponse;
import com.orderflow.order.dto.request.CreateOrderRequest;
import com.orderflow.order.dto.request.OrderItemRequest;
import com.orderflow.order.dto.response.OrderItemResponse;
import com.orderflow.order.dto.response.OrderResponse;
import com.orderflow.order.dto.response.ProductInfoResponse;
import com.orderflow.order.entity.Order;
import com.orderflow.order.entity.OrderItem;
import com.orderflow.order.enums.OrderStatus;
import com.orderflow.order.event.OrderCreatedEvent;
import com.orderflow.order.exception.InsufficientStockException;
import com.orderflow.order.exception.OrderNotFoundException;
import com.orderflow.order.producer.OrderEventProducer;
import com.orderflow.order.repository.OrderItemRepository;
import com.orderflow.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final InventoryClient inventoryClient;
    private final OrderEventProducer orderEventProducer;

    @Transactional
    public OrderResponse createOrder(
            CreateOrderRequest request,
            UUID authenticatedUserId) {

        Order order = Order.builder()
                .userId(authenticatedUserId)
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        List<OrderItem> orderItems = new ArrayList<>();

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.getItems()) {

            ProductInfoResponse product =
                    productClient.getProduct(itemRequest.getProductId());

            InventoryAvailabilityRequest availabilityRequest =
                    InventoryAvailabilityRequest.builder()
                            .productId(itemRequest.getProductId())
                            .quantity(itemRequest.getQuantity())
                            .build();

            InventoryAvailabilityResponse availabilityResponse =
                    inventoryClient.checkAvailability(availabilityRequest);

            if (!availabilityResponse.isAvailable()) {
                throw new InsufficientStockException(
                        "Insufficient stock for product: " + product.getId());
            }

            InventoryReservationRequest reservationRequest =
                    InventoryReservationRequest.builder()
                            .productId(itemRequest.getProductId())
                            .quantity(itemRequest.getQuantity())
                            .build();

            InventoryReservationResponse reservationResponse =
                    inventoryClient.reserveStock(reservationRequest);

            if (!reservationResponse.isSuccess()) {
                throw new InsufficientStockException(
                        "Failed to reserve stock for product: "
                                + itemRequest.getProductId());
            }

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

        Order savedOrder = orderRepository.saveAndFlush(order);

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getId())
                .userId(savedOrder.getUserId())
                .amount(savedOrder.getTotalAmount())
                .build();

        orderEventProducer.publishOrderCreated(event);

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
    public OrderResponse getOrderById(
            UUID id,
            UUID authenticatedUserId,
            String role) {

        Order order = orderRepository.findById(id)
                .orElseThrow(() ->
                        new OrderNotFoundException(id.toString()));

        // USER can access only their own order
        if (!"ADMIN".equals(role)
                && !order.getUserId().equals(authenticatedUserId)) {

            throw new OrderNotFoundException(id.toString());
        }

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
