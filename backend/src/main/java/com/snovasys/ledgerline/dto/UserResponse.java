package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.Role;
import com.snovasys.ledgerline.model.User;

import java.time.LocalDateTime;

public record UserResponse(
    Long id,
    String username,
    String email,
    String fullName,
    Role role,
    LocalDateTime createdAt
) {
    public static UserResponse fromEntity(User user) {
        return new UserResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            user.getCreatedAt()
        );
    }
}
