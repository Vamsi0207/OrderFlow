package com.orderflow.order.checkout;
import java.math.BigDecimal;
import java.util.*;
public record BuyNowResponse(UUID checkoutAttemptId,String workflowStatus,String code,UUID orderId,String orderStatus,
        BigDecimal totalAmount,String currency,List<Item> items,Payment payment) {
    public record Item(UUID productId,int quantity,BigDecimal unitPrice) {}
    public record Payment(UUID id,String status,String initializationState,String clientSecret) {}
}
