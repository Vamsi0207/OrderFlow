package com.orderflow.gateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"eureka.client.enabled=false"})
class WebhookRoutingTest {
    static HttpServer backend;
    static AtomicReference<String> body=new AtomicReference<>(),signature=new AtomicReference<>();
    static {
        try {
            backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            backend.createContext("/api/v1/payments",exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                signature.set(exchange.getRequestHeaders().getFirst("Stripe-Signature"));
                byte[] response="routed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
            });backend.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry r) {
        r.add("spring.cloud.gateway.server.webflux.routes[0].id",() -> "payment-routing-test");
        r.add("spring.cloud.gateway.server.webflux.routes[0].uri",() -> "http://127.0.0.1:"+backend.getAddress().getPort());
        r.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]",() -> "Path=/api/v1/payments/**");
    }
    @LocalServerPort int port;
    @AfterAll static void stop(){backend.stop(0);}
    @Test void exactWebhookIsRoutedWithUnchangedSignatureAndRawBody() throws Exception {
        String payload="""
                { "raw": true }
                """;
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/webhook"))
                .header("Stripe-Signature","t=test,v1=signature").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(body.get()).isEqualTo(payload);assertThat(signature.get()).isEqualTo("t=test,v1=signature");
    }
    @Test void ordinaryPaymentAndOtherWebhookMethodsRemainProtected() throws Exception {
        var client=HttpClient.newHttpClient();
        for(String path:java.util.List.of("/api/v1/payments","/api/v1/payments/webhook/child")) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/webhook")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
}
