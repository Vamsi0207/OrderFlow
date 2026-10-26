package com.orderflow.order.outbox;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface OrderNotificationRepository extends JpaRepository<OrderNotification,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from OrderNotification n where n.orderId=:id")
    Optional<OrderNotification> findLocked(@Param("id") UUID id);
    @Query(value="select order_id from order_notifications where not published and attempts<10 and (next_attempt_at is null or next_attempt_at<=current_timestamp) order by next_attempt_at nulls first,order_id limit 20",nativeQuery=true)
    List<UUID> due();
}
