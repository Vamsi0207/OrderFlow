package com.orderflow.auth.service;

import com.orderflow.auth.dto.request.LoginRequest;
import com.orderflow.auth.dto.request.RegisterRequest;
import com.orderflow.auth.dto.response.AuthResponse;
import com.orderflow.auth.dto.response.UserResponse;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.enums.Role;
import com.orderflow.auth.exception.EmailAlreadyExistsException;
import com.orderflow.auth.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_shouldReturnUserResponse_whenRegistrationIsSuccessful() {

        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setEmail("john@example.com");
        request.setPassword("password123");

        UUID userId = UUID.randomUUID();

        User savedUser = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        when(userRepository.existsByEmail("john@example.com"))
                .thenReturn(false);

        when(passwordEncoder.encode("password123"))
                .thenReturn("encodedPassword");

        when(userRepository.save(any(User.class)))
                .thenReturn(savedUser);

        // Act
        UserResponse response = authService.register(request);

        // Assert
        assertNotNull(response);
        assertEquals(userId, response.getId());
        assertEquals("John", response.getFirstName());
        assertEquals("Doe", response.getLastName());
        assertEquals("john@example.com", response.getEmail());
        assertEquals(Role.USER, response.getRole());

        // Verify interactions
        verify(userRepository).existsByEmail("john@example.com");
        verify(passwordEncoder).encode("password123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldThrowException_whenEmailAlreadyExists() {

        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setEmail("john@example.com");
        request.setPassword("password123");

        when(userRepository.existsByEmail("john@example.com"))
                .thenReturn(true);

        // Act & Assert
        EmailAlreadyExistsException exception = assertThrows(
                EmailAlreadyExistsException.class,
                () -> authService.register(request)
        );

        assertEquals(
                "User with email 'john@example.com' already exists.",
                exception.getMessage()
        );

        // Verify that no user is saved and password is not encoded
        verify(userRepository).existsByEmail("john@example.com");
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void login_shouldReturnAuthResponse_whenCredentialsAreValid() {

        // Arrange
        LoginRequest request = new LoginRequest();
        request.setEmail("john@example.com");
        request.setPassword("password123");

        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .password("encodedPassword")
                .role(Role.USER)
                .build();

        String token = "test-jwt-token";

        when(userRepository.findByEmail("john@example.com"))
                .thenReturn(Optional.of(user));

        when(jwtService.generateToken(user))
                .thenReturn(token);

        // Act
        AuthResponse response = authService.login(request);

        // Assert
        assertNotNull(response);
        assertEquals(token, response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());

        // Verify authentication was performed
        verify(authenticationManager).authenticate(
                any(UsernamePasswordAuthenticationToken.class)
        );

        // Verify user lookup and JWT generation
        verify(userRepository).findByEmail("john@example.com");
        verify(jwtService).generateToken(user);
    }

    @Test
    void login_shouldThrowException_whenCredentialsAreInvalid() {

        // Arrange
        LoginRequest request = new LoginRequest();
        request.setEmail("john@example.com");
        request.setPassword("wrongPassword");

        when(authenticationManager.authenticate(
                any(UsernamePasswordAuthenticationToken.class)
        )).thenThrow(
                new AuthenticationServiceException("Invalid credentials")
        );

        // Act & Assert
        AuthenticationServiceException exception = assertThrows(
                AuthenticationServiceException.class,
                () -> authService.login(request)
        );

        assertEquals("Invalid credentials", exception.getMessage());

        // Authentication failed, so these operations must never happen
        verify(authenticationManager).authenticate(
                any(UsernamePasswordAuthenticationToken.class)
        );

        verify(userRepository, never()).findByEmail(anyString());
        verify(jwtService, never()).generateToken(any(User.class));
    }
}
