package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.dto.AuthRequest;
import com.snovasys.ledgerline.dto.AuthResponse;
import com.snovasys.ledgerline.dto.RegisterRequest;
import com.snovasys.ledgerline.dto.UserResponse;
import com.snovasys.ledgerline.model.Role;
import com.snovasys.ledgerline.model.User;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.JwtUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtUtil jwtUtil,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.username())) {
            throw new IllegalArgumentException("Username '" + req.username() + "' is already taken");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email '" + req.email() + "' is already registered");
        }

        Role role = req.role() != null ? req.role() : Role.ROLE_ACCOUNT_HOLDER;
        User user = new User(
                req.username().trim(),
                req.email().trim().toLowerCase(),
                passwordEncoder.encode(req.password()),
                req.fullName().trim(),
                role
        );

        User saved = userRepository.save(user);
        auditService.logEvent(saved.getUsername(), "USER_REGISTERED", "users/" + saved.getId(), "SUCCESS", "Role: " + role, "0.0.0.0");
        return UserResponse.fromEntity(saved);
    }

    public AuthResponse login(AuthRequest req) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username().trim(), req.password())
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole().name(), user.getId());
        auditService.logEvent(user.getUsername(), "USER_LOGIN", "auth/login", "SUCCESS", "JWT issued", "0.0.0.0");

        return new AuthResponse(token, user.getId(), user.getUsername(), user.getFullName(), user.getRole());
    }

    public User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            throw new IllegalStateException("No authenticated user found in security context");
        }
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found in database"));
    }
}
