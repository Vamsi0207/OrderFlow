package com.orderflow.auth.controller;

import com.orderflow.auth.dto.request.LoginRequest;
import com.orderflow.auth.dto.request.RegisterRequest;
import com.orderflow.auth.dto.response.AuthResponse;
import com.orderflow.auth.dto.response.UserResponse;
import com.orderflow.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Valid @RequestBody RegisterRequest request) {
                System.out.println(">>> Register endpoint hit");
        UserResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);

        return ResponseEntity.ok(response);
    }
    @GetMapping("/me")
public String me(Authentication authentication) {
    return authentication.getName();
}
}