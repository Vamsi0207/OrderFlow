package com.orderflow.payment.repository;

import com.orderflow.payment.entity.Payment;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findLocked(@Param("id") UUID id);

    List<Payment> findByUserId(UUID userId);

    boolean existsByOrderId(UUID orderId);

    Optional<Payment> findByOrderId(UUID orderId);

    @Query("select p.id from Payment p where p.stripePaymentIntentId = :intentId")
    Optional<UUID> findIdByStripePaymentIntentId(@Param("intentId") String intentId);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);
}
