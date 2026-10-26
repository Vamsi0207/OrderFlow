package com.orderflow.inventory.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.orderId = :id")
    Optional<StockReservation> findLocked(@Param("id") UUID id);
}
