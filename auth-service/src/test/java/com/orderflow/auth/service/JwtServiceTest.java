package com.orderflow.auth.service;

import com.orderflow.auth.config.JwtProperties;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.enums.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    private JwtProperties jwtProperties;

    private final String secret =
            "my-super-secret-key-that-is-at-least-32-characters-long";

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();

        jwtProperties.setSecret(secret);
        jwtProperties.setExpiration(3600000L);

        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void generateToken_shouldContainUserClaims() {

        // Arrange
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        // Act
        String token = jwtService.generateToken(user);

        // Assert
        assertNotNull(token);
        assertFalse(token.isBlank());

        SecretKey key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals("john@example.com", claims.getSubject());
        assertEquals(userId.toString(), claims.get("userId"));
        assertEquals("USER", claims.get("role"));

        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());

        assertTrue(
                claims.getExpiration().after(new Date())
        );
    }

    @Test
    void extractUsername_shouldReturnEmailFromToken() {

        // Arrange
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        String token = jwtService.generateToken(user);

        // Act
        String username = jwtService.extractUsername(token);

        // Assert
        assertEquals("john@example.com", username);
    }

    @Test
    void isTokenValid_shouldReturnTrue_whenTokenBelongsToUser() {

        // Arrange
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        String token = jwtService.generateToken(user);

        org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User
                        .withUsername("john@example.com")
                        .password("encodedPassword")
                        .roles("USER")
                        .build();

        // Act
        boolean valid = jwtService.isTokenValid(token, userDetails);

        // Assert
        assertTrue(valid);
    }

    @Test
    void isTokenValid_shouldReturnFalse_whenTokenBelongsToDifferentUser() {

        // Arrange
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        String token = jwtService.generateToken(user);

        org.springframework.security.core.userdetails.UserDetails differentUser =
                org.springframework.security.core.userdetails.User
                        .withUsername("jane@example.com")
                        .password("encodedPassword")
                        .roles("USER")
                        .build();

        // Act
        boolean valid = jwtService.isTokenValid(token, differentUser);

        // Assert
        assertFalse(valid);
    }
}
