package com.orderflow.order.repository;

import com.orderflow.order.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    Optional<Order> findLocked(@org.springframework.data.repository.query.Param("id") UUID id);

    List<Order> findByUserId(UUID userId);

    @EntityGraph(attributePaths = "orderItems")
    Optional<Order> findWithOrderItemsById(UUID id);
}
