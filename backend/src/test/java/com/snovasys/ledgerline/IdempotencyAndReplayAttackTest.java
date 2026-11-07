package com.snovasys.ledgerline;

import com.snovasys.ledgerline.controller.TransactionController;
import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransactionResponse;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.exception.IdempotencyConflictException;
import com.snovasys.ledgerline.model.Account;
import com.snovasys.ledgerline.model.AccountType;
import com.snovasys.ledgerline.model.Role;
import com.snovasys.ledgerline.model.User;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class IdempotencyAndReplayAttackTest {

    @Autowired
    private TransactionController transactionController;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account sourceAccount;
    private Account destAccount;
    private User alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.findByUsername("alice").orElseGet(() -> {
            User u = new User("alice", "alice@test.com", "hash", "Alice", Role.ROLE_ACCOUNT_HOLDER);
            return userRepository.save(u);
        });

        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("alice", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        sourceAccount = accountRepository.save(new Account("IDEM-SRC-" + suffix, alice, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));
        destAccount = accountRepository.save(new Account("IDEM-DST-" + suffix, alice, AccountType.USER_SAVINGS, BigDecimal.ZERO, "USD"));

        ledgerService.deposit(new DepositRequest(sourceAccount.getId(), new BigDecimal("200.00"), "Deposit seed"), null, "127.0.0.1");
    }

    @Test
    @DisplayName("Threat Test: Replay attack with same Idempotency-Key must not execute second charge")
    void testReplayAttackPrevention() {
        String idempotencyKey = "UNIQUE-IDEM-KEY-99999";
        TransferRequest request = new TransferRequest(sourceAccount.getId(), destAccount.getId(), new BigDecimal("50.00"), "Rent");
        MockHttpServletRequest mockReq = new MockHttpServletRequest();

        // 1. First execution
        ResponseEntity<TransactionResponse> firstResponse = transactionController.transfer(request, idempotencyKey, mockReq);
        assertNotNull(firstResponse.getBody());
        assertEquals(200, firstResponse.getStatusCode().value());
        assertNull(firstResponse.getHeaders().getFirst("Idempotent-Replayed"));

        // 2. Second execution (Replay attack / network retry)
        ResponseEntity<TransactionResponse> secondResponse = transactionController.transfer(request, idempotencyKey, mockReq);
        assertNotNull(secondResponse.getBody());
        assertEquals(200, secondResponse.getStatusCode().value());
        assertEquals("true", secondResponse.getHeaders().getFirst("Idempotent-Replayed"),
                "Replayed response must include Idempotent-Replayed header");
        assertEquals(firstResponse.getBody().referenceId(), secondResponse.getBody().referenceId(),
                "Must return identical transaction reference");

        // 3. Verify balance: $200 - $50 = $150. NOT $100!
        Account currentSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
        assertEquals(0, currentSource.getBalance().compareTo(new BigDecimal("150.0000")),
                "Money must only be debited once regardless of replay attempts");
    }

    @Test
    @DisplayName("Threat Test: Reusing Idempotency-Key with different payload must fail with IdempotencyConflictException")
    void testIdempotencyPayloadTampering() {
        String idempotencyKey = "TAMPER-KEY-12345";
        TransferRequest initialReq = new TransferRequest(sourceAccount.getId(), destAccount.getId(), new BigDecimal("10.00"), "Lunch");
        MockHttpServletRequest mockReq = new MockHttpServletRequest();

        // Initial request succeeds
        transactionController.transfer(initialReq, idempotencyKey, mockReq);

        // Attacker changes amount to $100.00 using same key
        TransferRequest tamperedReq = new TransferRequest(sourceAccount.getId(), destAccount.getId(), new BigDecimal("100.00"), "Lunch");

        assertThrows(IdempotencyConflictException.class, () -> {
            transactionController.transfer(tamperedReq, idempotencyKey, mockReq);
        }, "Tampering with payload using an existing idempotency key must be blocked");
    }
}
