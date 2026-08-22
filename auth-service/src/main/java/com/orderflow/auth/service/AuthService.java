package com.orderflow.auth.service;

import com.orderflow.auth.dto.request.LoginRequest;
import com.orderflow.auth.dto.request.RegisterRequest;
import com.orderflow.auth.dto.response.AuthResponse;
import com.orderflow.auth.dto.response.UserResponse;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.enums.Role;
import com.orderflow.auth.exception.EmailAlreadyExistsException;
import com.orderflow.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
        
    public UserResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();

        User savedUser = userRepository.save(user);

        return UserResponse.builder()
                .id(savedUser.getId())
                .firstName(savedUser.getFirstName())
                .lastName(savedUser.getLastName())
                .email(savedUser.getEmail())
                .role(savedUser.getRole())
                .createdAt(savedUser.getCreatedAt())
                .build();
    }

    public AuthResponse login(LoginRequest request) {

    authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                    request.getEmail(),
                    request.getPassword()
            )
    );

    User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow();

    String token = jwtService.generateToken(user);

    return AuthResponse.builder()
            .accessToken(token)
            .build();
}
}
