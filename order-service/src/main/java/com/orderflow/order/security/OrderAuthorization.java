package com.orderflow.order.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

@Component
public class OrderAuthorization {

    public void requireAdmin(String role) {

        if (!"ADMIN".equals(role)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Admin access required"
            );
        }
    }
}
