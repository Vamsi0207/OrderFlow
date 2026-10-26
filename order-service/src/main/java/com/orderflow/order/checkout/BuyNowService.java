package com.orderflow.order.checkout;
import com.orderflow.order.client.ProductClient;
import com.orderflow.order.dto.response.ProductInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.*;
@lombok.extern.slf4j.Slf4j
@Service @RequiredArgsConstructor
public class BuyNowService {
    private final CheckoutAttemptService attempts;
    private final CheckoutWorkflowStore store;
    private final ProductClient products;
    private final CheckoutInventoryClient inventory;
    private final CheckoutPaymentClient payments;
    public record Result(int status,BuyNowResponse body) {}

    @Transactional(propagation=Propagation.NEVER)
    public Result buy(BuyActor actor,String key,CheckoutRequest request) {
        CheckoutPayload.from(request); // validation before persisting intent
        var attempt=attempts.begin(actor.userId(),key,request);
        UUID id=attempt.getId(),owner=UUID.randomUUID();
        if(!store.claim(id,owner))return new Result(202,store.snapshot(id,"CHECKOUT_IN_PROGRESS",null));
        String step="PRODUCT";
        try {
            var state=store.get(id);
            if(state.getOrderId()!=null) {
                var snapshot=store.snapshot(id,null,null);
                if(!"PENDING".equals(snapshot.orderStatus())) {
                    var live=payments.lookup(state.getOrderId(),actor);validate(live,snapshot,actor);
                    boolean cancelled="CANCELLED".equals(snapshot.orderStatus());
                    store.terminal(id,owner,live,cancelled);
                    return new Result(cancelled?409:200,store.snapshot(id,null,live));
                }
                if(state.getStatus()==CheckoutAttempt.Status.RECOVERY_REQUIRED)
                    return new Result(409,store.snapshot(id,null,null));
            }
            if(state.getOrderId()==null) {
                var quantities=quantities(request);var quotes=new HashMap<UUID,ProductInfoResponse>();
                for(UUID productId:quantities.keySet()) {
                    store.heartbeat(id,owner);var quote=products.getProduct(productId);
                    if(quote==null || !productId.equals(quote.getId()) || quote.getPrice()==null || quote.getPrice().signum()<=0 || quote.getPrice().scale()>2)
                        throw new IllegalArgumentException("Invalid authoritative product price");
                    quotes.put(productId,quote);
                }
                step="ORDER";store.createOrder(id,owner,quantities,quotes);state=store.get(id);
            }
            step="INVENTORY";
            if(state.getStatus()==CheckoutAttempt.Status.ORDER_CREATED) {
                store.heartbeat(id,owner);
                var reservation=inventory.reserve(state.getOrderId(),request);
                if(reservation==null || !state.getOrderId().equals(reservation.orderId()) || !quantities(request).equals(reservation.items())
                        || !"RESERVED".equals(reservation.state())) {
                    store.reconcile(id,owner,"RESERVATION_RECONCILIATION_REQUIRED");
                    return new Result(409,store.snapshot(id,null,null));
                }
                store.reserved(id,owner);
            }
            step="PAYMENT";store.heartbeat(id,owner);
            var snapshot=store.snapshot(id,null,null);
            var result=payments.initialize(snapshot.orderId(),actor);
            if(result==null)throw new IllegalStateException("Missing payment response");
            var live=result.payment();
            if(live!=null && result.httpStatus()!=201)live=new CheckoutPaymentClient.Payment(live.id(),live.orderId(),live.userId(),live.amount(),live.currency(),live.status(),live.initializationState(),null);
            if(live!=null && live.id()!=null)validate(live,snapshot,actor);
            if(result.httpStatus()==409 && (live==null || live.id()==null)) {
                live=payments.lookup(snapshot.orderId(),actor);validate(live,snapshot,actor);
                if("COMPLETED".equals(live.status()) || "FAILED".equals(live.status())) {
                    store.payment(id,owner,live,202);
                    return new Result(202,store.snapshot(id,"AWAITING_VERIFIED_ORDER_EVENT",live));
                }
            }
            if(result.httpStatus()==201 && (live==null || live.id()==null || !"READY".equals(live.initializationState())))
                throw new IllegalStateException("Invalid ready payment response");
            store.payment(id,owner,live,result.httpStatus());
            return new Result(result.httpStatus(),store.snapshot(id,null,live));
        } catch(LeaseLostException fenced) {
            return new Result(202,store.snapshot(id,"CHECKOUT_IN_PROGRESS",null));
        } catch(WebClientResponseException downstream) {
            int http=downstream.getStatusCode().value();
            if(step.equals("PAYMENT"))http=202; // May already have committed a PaymentIntent. Never compensate blindly.
            else if(http==404)http=404;
            else if(step.equals("INVENTORY") && (http==400 || http==409))http=409;
            else http=503;
            return failed(id,owner,http,step+(http==409?"_INSUFFICIENT_OR_CONFLICT":http==404?"_NOT_FOUND":"_UNAVAILABLE"));
        } catch(IllegalArgumentException | ArithmeticException invalid) {
            return failed(id,owner,400,"INVALID_CHECKOUT");
        } catch(RuntimeException unavailable) {
            return failed(id,owner,step.equals("PAYMENT")?202:503,step+"_UNAVAILABLE");
        } finally {store.release(id,owner);}
    }
    private Result failed(UUID id,UUID owner,int status,String code) {
        log.warn("Checkout recovery attempt={} code={} http={}",id,code,status);
        try {store.failure(id,owner,status,code);}catch(LeaseLostException lost){return new Result(202,store.snapshot(id,"CHECKOUT_IN_PROGRESS",null));}
        return new Result(status,store.snapshot(id,null,null));
    }
    private void validate(CheckoutPaymentClient.Payment p,BuyNowResponse order,BuyActor actor) {
        if(p==null || p.id()==null || !order.orderId().equals(p.orderId()) || !actor.userId().equals(p.userId()) || p.amount()==null
                || order.totalAmount().compareTo(p.amount())!=0 || !order.currency().equalsIgnoreCase(p.currency()))
            throw new IllegalStateException("Payment/order snapshot mismatch");
    }
    static SortedMap<UUID,Integer> quantities(CheckoutRequest request) {
        var result=new TreeMap<UUID,Integer>();for(var item:request.items())result.merge(item.productId(),item.quantity(),Math::addExact);return result;
    }
}
