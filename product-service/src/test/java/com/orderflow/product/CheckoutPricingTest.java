package com.orderflow.product;

import com.orderflow.product.service.ProductService;
import com.orderflow.product.repository.ProductRepository;
import com.orderflow.product.entity.Product;
import com.orderflow.product.exception.ProductNotFoundException;
import com.orderflow.product.dto.response.ProductInfoResponse;
import com.orderflow.product.dto.response.ProductResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "eureka.client.enabled=false")
class CheckoutPricingTest {
    @Autowired ProductService service;
    @Autowired ProductRepository repository;
    @Autowired CacheManager caches;
    @Autowired JdbcTemplate jdbc;

    Product product() {
        return repository.saveAndFlush(Product.builder().name("checkout-price-test").price(new BigDecimal("10.00"))
                .stock(5).category("test").build());
    }
    void cleanup(UUID id) {
        caches.getCache("products").evict(id);caches.getCache("productInfo").evict(id);repository.deleteById(id);
    }

    @Test void checkoutBypassesRealStaleRedisWhileNormalLookupsKeepCaching() {
        var product=product();UUID id=product.getId();
        try {
            assertThat(caches.getClass().getName()).contains("Redis");
            service.getProductInfo(id);service.getProductById(id);
            assertThat(caches.getCache("productInfo").get(id,ProductInfoResponse.class).getPrice()).isEqualByComparingTo("10.00");
            jdbc.update("update products set price=25.50 where id=?",id); // Deliberately leave old cache entries.
            assertThat(service.getProductInfo(id).getPrice()).isEqualByComparingTo("10.00");
            assertThat(service.getProductById(id).getPrice()).isEqualByComparingTo("10.00");
            assertThat(service.getCheckoutPrice(id).getPrice()).isEqualByComparingTo("25.50");
            assertThat(caches.getCache("products").get(id,ProductResponse.class).getPrice()).isEqualByComparingTo("10.00");
            jdbc.update("update products set price=30.00 where id=?",id);
            assertThat(service.getCheckoutPrice(id).getPrice()).isEqualByComparingTo("30.00");
        } finally {cleanup(id);}
    }

    @Test void deletedProductCannotBePurchasedThroughAStaleCache() {
        var product=product();UUID id=product.getId();
        try {
            service.getProductInfo(id);
            repository.deleteById(id);
            assertThat(service.getProductInfo(id)).isNotNull();
            assertThatThrownBy(() -> service.getCheckoutPrice(id)).isInstanceOf(ProductNotFoundException.class);
        } finally {cleanup(id);}
    }

    @Test void invalidDatabasePriceIsRejected() {
        var product=product();UUID id=product.getId();
        try {
            jdbc.update("update products set price=0 where id=?",id);
            assertThatThrownBy(() -> service.getCheckoutPrice(id)).isInstanceOf(IllegalStateException.class);
        } finally {cleanup(id);}
    }
}
