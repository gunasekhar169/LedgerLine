package com.snovasys.ledgerline;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.LedgerProofResponse;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawalRequest;
import com.snovasys.ledgerline.model.Account;
import com.snovasys.ledgerline.model.AccountType;
import com.snovasys.ledgerline.model.Role;
import com.snovasys.ledgerline.model.User;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.service.LedgerService;
import com.snovasys.ledgerline.service.LedgerVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class LedgerMathematicalProofTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerVerificationService verificationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account acc1;
    private Account acc2;
    private User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseGet(() ->
                userRepository.save(new User("admin", "admin@ledgerline.io", "hash", "Admin", Role.ROLE_ADMIN)));

        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("admin", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        acc1 = accountRepository.save(new Account("PROOF-ACC-01-" + suffix, admin, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));
        acc2 = accountRepository.save(new Account("PROOF-ACC-02-" + suffix, admin, AccountType.USER_SAVINGS, BigDecimal.ZERO, "USD"));
    }

    @Test
    @DisplayName("Mathematical Invariant: Sum(Debits) must equal Sum(Credits) across sequence of operations")
    void testLedgerBooksAlwaysBalance() {
        // Step 1: Deposit $1,000 into acc1
        ledgerService.deposit(new DepositRequest(acc1.getId(), new BigDecimal("1000.00"), "Deposit $1000"), null, "127.0.0.1");

        // Step 2: Transfer $350 from acc1 to acc2
        ledgerService.transfer(new TransferRequest(acc1.getId(), acc2.getId(), new BigDecimal("350.00"), "Transfer $350"), null, "127.0.0.1");

        // Step 3: Withdraw $100 from acc2
        ledgerService.withdraw(new WithdrawalRequest(acc2.getId(), new BigDecimal("100.00"), "Withdraw $100"), null, "127.0.0.1");

        // Step 4: Run audit mathematical verification
        LedgerProofResponse proof = verificationService.verifySystemLedger("auditor");

        assertTrue(proof.isBalanced(), "Ledger must be proven balanced");
        assertEquals(0, proof.netDiscrepancy().compareTo(new BigDecimal("0.0000")), "Net discrepancy between Debits and Credits must be zero");
        assertEquals(proof.totalDebits(), proof.totalCredits(), "Total Debits must exactly equal Total Credits");
        assertTrue(proof.ledgerIntegrityHash().length() > 0, "Cryptographic integrity hash must be computed");
    }
}
