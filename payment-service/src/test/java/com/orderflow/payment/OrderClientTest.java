package com.orderflow.payment;
import com.orderflow.payment.client.OrderClient;
import com.orderflow.payment.security.PaymentActor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;
class OrderClientTest {
    @Test void usesProtectedGatewayRouteWithOriginalBearerAndPreservesAuthoritativeFields() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        UUID id=UUID.randomUUID(),owner=UUID.randomUUID();
        server.expect(requestTo("http://gateway.test/api/v1/orders/"+id)).andExpect(header("Authorization","Bearer original-user-token"))
                .andRespond(withSuccess("{\"id\":\""+id+"\",\"userId\":\""+owner+"\",\"status\":\"PENDING\",\"totalAmount\":12.50,\"currency\":\"USD\"}",MediaType.APPLICATION_JSON));
        var order=new OrderClient(builder,"http://gateway.test").get(id,new PaymentActor(owner,"USER","Bearer original-user-token"));
        assertThat(order.totalAmount()).isEqualByComparingTo("12.50");assertThat(order.currency()).isEqualTo("USD");server.verify();
    }
    @Test void missingOrderIs404AndRemoteErrorsFailClosed() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();UUID id=UUID.randomUUID();
        server.expect(requestTo("http://gateway.test/api/v1/orders/"+id)).andRespond(withResourceNotFound());
        var client=new OrderClient(builder,"http://gateway.test");
        assertThatThrownBy(() -> client.get(id,new PaymentActor(UUID.randomUUID(),"USER","Bearer token")))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(404));server.verify();
    }
}
