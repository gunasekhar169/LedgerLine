package com.snovasys.ledgerline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.dto.*;
import com.snovasys.ledgerline.model.IdempotencyRecord;
import com.snovasys.ledgerline.model.User;
import com.snovasys.ledgerline.service.AuthService;
import com.snovasys.ledgerline.service.IdempotencyService;
import com.snovasys.ledgerline.service.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public TransactionController(LedgerService ledgerService,
                                 IdempotencyService idempotencyService,
                                 AuthService authService,
                                 ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        User currentUser = authService.getCurrentUser();
        String clientIp = request.getRemoteAddr();

        // Idempotency verification
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            Optional<IdempotencyRecord> existing = idempotencyService.checkIdempotency(idempotencyKey, currentUser.getId(), hash);
            if (existing.isPresent()) {
                try {
                    TransactionResponse cachedResponse = objectMapper.readValue(existing.get().getResponseBody(), TransactionResponse.class);
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Idempotent-Replayed", "true");
                    return new ResponseEntity<>(cachedResponse, headers, HttpStatus.valueOf(existing.get().getResponseStatus()));
                } catch (Exception e) {
                    // Fallthrough if parsing cached response fails
                }
            }
        }

        TransactionResponse response = ledgerService.transfer(req, idempotencyKey, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @Valid @RequestBody DepositRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        User currentUser = authService.getCurrentUser();
        String clientIp = request.getRemoteAddr();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            Optional<IdempotencyRecord> existing = idempotencyService.checkIdempotency(idempotencyKey, currentUser.getId(), hash);
            if (existing.isPresent()) {
                try {
                    TransactionResponse cachedResponse = objectMapper.readValue(existing.get().getResponseBody(), TransactionResponse.class);
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Idempotent-Replayed", "true");
                    return new ResponseEntity<>(cachedResponse, headers, HttpStatus.valueOf(existing.get().getResponseStatus()));
                } catch (Exception e) {
                    // Fallthrough
                }
            }
        }

        TransactionResponse response = ledgerService.deposit(req, idempotencyKey, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @Valid @RequestBody WithdrawalRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        User currentUser = authService.getCurrentUser();
        String clientIp = request.getRemoteAddr();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            Optional<IdempotencyRecord> existing = idempotencyService.checkIdempotency(idempotencyKey, currentUser.getId(), hash);
            if (existing.isPresent()) {
                try {
                    TransactionResponse cachedResponse = objectMapper.readValue(existing.get().getResponseBody(), TransactionResponse.class);
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Idempotent-Replayed", "true");
                    return new ResponseEntity<>(cachedResponse, headers, HttpStatus.valueOf(existing.get().getResponseStatus()));
                } catch (Exception e) {
                    // Fallthrough
                }
            }
        }

        TransactionResponse response = ledgerService.withdraw(req, idempotencyKey, clientIp);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/statement/{accountId}")
    public ResponseEntity<StatementResponse> getAccountStatement(@PathVariable Long accountId) {
        StatementResponse statement = ledgerService.getAccountStatement(accountId);
        return ResponseEntity.ok(statement);
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsForAccount(@PathVariable Long accountId) {
        List<TransactionResponse> txList = ledgerService.getTransactionsForAccount(accountId);
        return ResponseEntity.ok(txList);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR')")
    public ResponseEntity<List<TransactionResponse>> getAllTransactions() {
        List<TransactionResponse> list = ledgerService.getAllTransactions();
        return ResponseEntity.ok(list);
    }
}
