package com.orderflow.order.checkout;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Component
public class BuyAuthenticationFilter extends OncePerRequestFilter {
    private final String secret;
    public BuyAuthenticationFilter(@Value("${jwt.secret}") String secret) {this.secret=secret;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {return !r.getRequestURI().equals("/api/v1/buy");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {
            String header=request.getHeader("Authorization");
            if(header==null || !header.startsWith("Bearer "))throw new IllegalArgumentException();
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(header.substring(7)).getPayload();
            if(claims.getExpiration()==null || !java.util.Set.of("USER","ADMIN").contains(claims.get("role",String.class)))throw new IllegalArgumentException();
            request.setAttribute("buyActor",new BuyActor(UUID.fromString(claims.get("userId",String.class)),header));
        } catch(Exception invalid) {
            response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");return;
        }
        chain.doFilter(request,response);
    }
}
