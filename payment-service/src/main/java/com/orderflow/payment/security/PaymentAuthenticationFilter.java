package com.orderflow.payment.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class PaymentAuthenticationFilter extends OncePerRequestFilter {
    public static final String ACTOR = "paymentActor";
    private final String secret;
    public PaymentAuthenticationFilter(@Value("${jwt.secret}") String secret) { this.secret=secret; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !(path.startsWith("/api/v1/payments") || path.startsWith("/internal/"))
                || ("POST".equals(request.getMethod()) && "/api/v1/payments/webhook".equals(path));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String authorization=request.getHeader("Authorization");
        try {
            if(authorization==null || !authorization.startsWith("Bearer "))throw new IllegalArgumentException();
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(authorization.substring(7)).getPayload();
            if(claims.getExpiration()==null || !claims.getExpiration().toInstant().isAfter(Instant.now()))throw new IllegalArgumentException();
            if(request.getRequestURI().startsWith("/internal/")) {
                String prefix="/internal/v1/payments/";
                if(!"GET".equals(request.getMethod()) || !request.getRequestURI().startsWith(prefix)
                        || !"order-service".equals(claims.getSubject())
                        || !"payment-status".equals(claims.get("purpose",String.class))
                        || claims.getAudience()==null || !claims.getAudience().contains("payment-service")
                        || !request.getRequestURI().substring(prefix.length()).equals(claims.get("paymentId",String.class)))
                    throw new IllegalArgumentException();
            } else {
                UUID user=UUID.fromString(claims.get("userId",String.class));
                String role=claims.get("role",String.class);
                if(!"USER".equals(role) && !"ADMIN".equals(role))throw new IllegalArgumentException();
                request.setAttribute(ACTOR,new PaymentActor(user,role,authorization));
            }
        } catch(Exception invalid) {
            response.setStatus(401);return;
        }
        chain.doFilter(request,response);
    }
}
