package com.orderflow.payment.client;
import com.orderflow.payment.security.PaymentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
@Component
public class OrderClient {
    private final RestClient client;
    public OrderClient(RestClient.Builder builder,@Value("${orderflow.gateway-url:http://GATEWAY}") String base) {
        client=builder.baseUrl(base).build();
    }
    public AuthoritativeOrder get(UUID id,PaymentActor actor) {
        try {
            // Reuse the existing protected public order route. Gateway verifies the JWT
            // and overwrites identity headers; no unrestricted internal order route is added.
            return client.get().uri("/api/v1/orders/{id}",id).header("Authorization",actor.authorization())
                    .retrieve().body(AuthoritativeOrder.class);
        } catch(HttpClientErrorException e) {
            if(e.getStatusCode().value()==404)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
            if(e.getStatusCode().value()==401 || e.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Order access denied");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order validation unavailable");
        } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order validation unavailable");
        }
    }
}
