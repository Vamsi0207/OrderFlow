package com.orderflow.payment.service;
import com.orderflow.payment.client.*;
import com.orderflow.payment.security.PaymentActor;
import com.orderflow.payment.dto.request.CreatePaymentRequest;
import com.orderflow.payment.dto.response.PaymentResponse;
import com.orderflow.payment.entity.Payment;
import com.orderflow.payment.exception.PaymentNotFoundException;
import com.orderflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service @RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository repository;
    private final OrderClient orders;
    private final PaymentStore store;
    private final PaymentInitializer initializer;

    public PaymentResponse createPayment(CreatePaymentRequest request,PaymentActor actor) {
        var order=orders.get(request.getOrderId(),actor);
        if(order==null || !request.getOrderId().equals(order.id()))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid order response");
        if(!actor.userId().equals(order.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Order ownership required");
        if(Boolean.FALSE.equals(order.paymentEligible()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order inventory is not reserved");
        if(!"PENDING".equals(order.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is not eligible for payment");
        // Historical orders are INR; USD/EUR are also supported as two-decimal currencies.
        if(order.totalAmount()==null || order.totalAmount().signum()<=0 || order.totalAmount().scale()>2
                || order.currency()==null || !Set.of("INR","USD","EUR").contains(order.currency().toUpperCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid order pricing or unsupported currency");
        try { StripePaymentService.minorUnits(order.totalAmount()); }
        catch(ArithmeticException invalid){throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid order amount");}
        var payment=store.prepare(order);
        if(!actor.userId().equals(payment.getUserId()) || payment.getAmount().compareTo(order.totalAmount())!=0
                || !payment.getCurrency().equalsIgnoreCase(order.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment/order snapshot mismatch; reconciliation required");
        if(payment.getStatus()!=com.orderflow.payment.enums.PaymentStatus.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment is already terminal");
        return initializer.initialize(payment.getId());
    }
    public PaymentResponse getPaymentByOrder(UUID orderId,PaymentActor actor) {
        var p=repository.findByOrderId(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId.toString()));
        return getPaymentById(p.getId(),actor);
    }
    public PaymentResponse getPaymentById(UUID id,PaymentActor actor) {
        Payment p=repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id.toString()));
        if(!p.getUserId().equals(actor.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Payment ownership required");
        var order=orders.get(p.getOrderId(),actor);
        if(order==null || !p.getOrderId().equals(order.id()) || !actor.userId().equals(order.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Order ownership required");
        return PaymentInitializer.response(p,null);
    }
    public List<PaymentResponse> getAllPayments(PaymentActor actor) {
        // The old unscoped listing exposed other customers' payment records.
        return getPaymentsByUser(actor.userId(),actor);
    }
    public List<PaymentResponse> getPaymentsByUser(UUID userId,PaymentActor actor) {
        if(!userId.equals(actor.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Payment ownership required");
        return repository.findByUserId(userId).stream().map(p -> {
            var order=orders.get(p.getOrderId(),actor);
            if(order==null || !p.getOrderId().equals(order.id()) || !actor.userId().equals(order.userId()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Order ownership required");
            return PaymentInitializer.response(p,null);
        }).toList();
    }
}
