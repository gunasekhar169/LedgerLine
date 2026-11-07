package com.snovasys.ledgerline;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawalRequest;
import com.snovasys.ledgerline.exception.UnauthorizedAccountAccessException;
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
public class AccessControlAndIdorTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account aliceAccount;
    private Account bobAccount;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.findByUsername("alice").orElseGet(() ->
                userRepository.save(new User("alice", "alice@example.com", "hash", "Alice", Role.ROLE_ACCOUNT_HOLDER)));
        bob = userRepository.findByUsername("bob").orElseGet(() ->
                userRepository.save(new User("bob", "bob@example.com", "hash", "Bob", Role.ROLE_ACCOUNT_HOLDER)));

        aliceAccount = accountRepository.save(new Account("IDOR-ALICE-01", alice, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));
        bobAccount = accountRepository.save(new Account("IDOR-BOB-01", bob, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));

        // Authenticate as Alice to fund her account
        setAuth("alice");
        ledgerService.deposit(new DepositRequest(aliceAccount.getId(), new BigDecimal("300.00"), "Deposit"), null, "127.0.0.1");
    }

    @Test
    @DisplayName("Threat Test: Bob attempting to transfer from Alice's account (IDOR) must be blocked")
    void testPreventIdorTransfer() {
        // Switch session to Bob
        setAuth("bob");

        TransferRequest maliciousReq = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("50.00"), "Unauthorized theft");

        assertThrows(UnauthorizedAccountAccessException.class, () -> {
            ledgerService.transfer(maliciousReq, null, "192.168.1.100");
        }, "Bob must not be able to debit Alice's account");
    }

    @Test
    @DisplayName("Threat Test: Bob attempting to withdraw from Alice's account (IDOR) must be blocked")
    void testPreventIdorWithdrawal() {
        setAuth("bob");

        WithdrawalRequest maliciousReq = new WithdrawalRequest(aliceAccount.getId(), new BigDecimal("50.00"), "Theft");

        assertThrows(UnauthorizedAccountAccessException.class, () -> {
            ledgerService.withdraw(maliciousReq, null, "192.168.1.100");
        }, "Bob must not be able to withdraw from Alice's account");
    }

    @Test
    @DisplayName("Threat Test: Bob attempting to read Alice's transaction statement must be blocked")
    void testPreventIdorStatementRead() {
        setAuth("bob");

        assertThrows(UnauthorizedAccountAccessException.class, () -> {
            ledgerService.getAccountStatement(aliceAccount.getId());
        }, "Bob must not be able to view Alice's account statement");
    }

    private void setAuth(String username) {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User(username, "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }
}
