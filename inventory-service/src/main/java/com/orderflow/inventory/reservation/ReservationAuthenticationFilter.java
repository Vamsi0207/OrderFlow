package com.orderflow.inventory.reservation;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
@Component
public class ReservationAuthenticationFilter extends OncePerRequestFilter {
    private final String secret;
    public ReservationAuthenticationFilter(@Value("${jwt.secret}") String secret) {this.secret=secret;}
    private String path(HttpServletRequest r) {return URLDecoder.decode(r.getRequestURI(),StandardCharsets.UTF_8);}
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {return !path(r).startsWith("/api/v1/inventory/reservations");}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {
            String[] segments=path(r).split("/");
            if(segments.length!=6 && segments.length!=7)throw new IllegalArgumentException();
            String action=segments.length==7?segments[6]:"GET".equals(r.getMethod())?"read":"reserve";
            String method=switch(action){case "read" -> "GET";case "reserve" -> "PUT";case "release","deduct" -> "POST";default -> "";};
            String auth=r.getHeader("Authorization");if(auth==null || !auth.startsWith("Bearer ") || !method.equals(r.getMethod()))throw new IllegalArgumentException();
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(auth.substring(7)).getPayload();
            if(claims.getExpiration()==null || !"order-service".equals(claims.getSubject()) || claims.getAudience()==null || !claims.getAudience().contains("inventory-service")
                    || !"inventory-reservation".equals(claims.get("purpose",String.class)) || !segments[5].equals(claims.get("reservationId",String.class))
                    || !action.equals(claims.get("action",String.class)))throw new IllegalArgumentException();
        } catch(Exception denied){response.setStatus(401);return;}
        chain.doFilter(r,response);
    }
}
