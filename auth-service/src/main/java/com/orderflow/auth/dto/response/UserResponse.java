package com.orderflow.auth.dto.response;

import com.orderflow.auth.enums.Role;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class UserResponse {

    private UUID id;

    private String firstName;

    private String lastName;

    private String email;

    private Role role;

    private Instant createdAt;
}