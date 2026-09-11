package com.orderflow.auth;

import com.orderflow.auth.config.JwtProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(JwtProperties jwtProperties) {
        return args -> {
            System.out.println("JWT Secret: " + jwtProperties.getSecret());
            System.out.println("JWT Expiration: " + jwtProperties.getExpiration());
        };
    }
}
