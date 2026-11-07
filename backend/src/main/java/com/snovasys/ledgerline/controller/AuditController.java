package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.AuditLogResponse;
import com.snovasys.ledgerline.dto.LedgerProofResponse;
import com.snovasys.ledgerline.service.AuditService;
import com.snovasys.ledgerline.service.AuthService;
import com.snovasys.ledgerline.service.LedgerVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR')")
public class AuditController {

    private final AuditService auditService;
    private final LedgerVerificationService verificationService;
    private final AuthService authService;

    public AuditController(AuditService auditService,
                           LedgerVerificationService verificationService,
                           AuthService authService) {
        this.auditService = auditService;
        this.verificationService = verificationService;
        this.authService = authService;
    }

    @GetMapping("/logs")
    public ResponseEntity<List<AuditLogResponse>> getAuditLogs() {
        List<AuditLogResponse> logs = auditService.getAllLogs().stream()
                .map(AuditLogResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/verify-ledger")
    public ResponseEntity<LedgerProofResponse> verifyLedgerIntegrity() {
        String username = authService.getCurrentUser().getUsername();
        LedgerProofResponse proof = verificationService.verifySystemLedger(username);
        return ResponseEntity.ok(proof);
    }
}
