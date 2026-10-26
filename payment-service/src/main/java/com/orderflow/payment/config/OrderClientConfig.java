package com.orderflow.payment.config;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
@Configuration
public class OrderClientConfig {
    @Bean @LoadBalanced
    RestClient.Builder orderRestClientBuilder() {
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(5000);factory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(factory);
    }
}
