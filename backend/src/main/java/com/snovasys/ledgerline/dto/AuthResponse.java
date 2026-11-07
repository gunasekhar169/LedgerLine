package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.Role;

public record AuthResponse(
    String token,
    String tokenType,
    Long userId,
    String username,
    String fullName,
    Role role
) {
    public AuthResponse(String token, Long userId, String username, String fullName, Role role) {
        this(token, "Bearer", userId, username, fullName, role);
    }
}
