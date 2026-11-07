package com.snovasys.ledgerline;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawalRequest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
public class NegativeAndZeroAmountPreventionTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account account;

    @BeforeEach
    void setUp() {
        User admin = userRepository.findByUsername("admin").orElseGet(() ->
                userRepository.save(new User("admin", "admin@ledgerline.io", "hash", "Admin", Role.ROLE_ADMIN)));

        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("admin", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        account = accountRepository.save(new Account("ZERO-ACC-" + suffix, admin, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));
    }

    @Test
    @DisplayName("Threat Test: Negative and zero amounts must be rejected")
    void testRejectZeroAndNegativeAmounts() {
        // Zero amount deposit
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.deposit(new DepositRequest(account.getId(), BigDecimal.ZERO, "Zero deposit"), null, "127.0.0.1"));

        // Negative deposit
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.deposit(new DepositRequest(account.getId(), new BigDecimal("-50.00"), "Negative deposit"), null, "127.0.0.1"));

        // Negative withdrawal
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.withdraw(new WithdrawalRequest(account.getId(), new BigDecimal("-10.00"), "Negative withdraw"), null, "127.0.0.1"));

        // Negative transfer
        assertThrows(IllegalArgumentException.class, () ->
                ledgerService.transfer(new TransferRequest(account.getId(), account.getId(), new BigDecimal("-25.00"), "Negative transfer"), null, "127.0.0.1"));
    }
}
