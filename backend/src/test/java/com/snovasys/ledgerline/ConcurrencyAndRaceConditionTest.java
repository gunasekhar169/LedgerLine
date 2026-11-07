package com.snovasys.ledgerline;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.WithdrawalRequest;
import com.snovasys.ledgerline.exception.InsufficientFundsException;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ConcurrencyAndRaceConditionTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account testAccount;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.findByUsername("alice").orElseGet(() -> {
            User u = new User("alice", "alice@example.com", "hash", "Alice Smith", Role.ROLE_ACCOUNT_HOLDER);
            return userRepository.save(u);
        });

        // Set security context for Alice
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("alice", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        testAccount = new Account("TEST-CONC-" + suffix, testUser, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD");
        testAccount = accountRepository.save(testAccount);

        // Deposit exactly $100.00
        ledgerService.deposit(new DepositRequest(testAccount.getId(), new BigDecimal("100.00"), "Initial seed"), null, "127.0.0.1");
    }

    @Test
    @DisplayName("Threat Test: Concurrent withdrawals attempting to overdraw must not result in negative balance")
    void testConcurrentWithdrawalsPreventDoubleSpend() throws InterruptedException {
        int numberOfThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                // Prepare security context per thread
                org.springframework.security.core.userdetails.User principal =
                        new org.springframework.security.core.userdetails.User("alice", "hash",
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ACCOUNT_HOLDER")));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
                );

                readyLatch.countDown();
                try {
                    startLatch.await(); // Simultaneous execution
                    ledgerService.withdraw(new WithdrawalRequest(testAccount.getId(), new BigDecimal("100.00"), "Concurrent withdrawal"), null, "127.0.0.1");
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root != root.getCause()) {
                        root = root.getCause();
                    }
                    if (root instanceof InsufficientFundsException || (t.getMessage() != null && t.getMessage().contains("Insufficient funds"))) {
                        insufficientFundsCount.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Fire all 10 threads at once
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verification: Exactly ONE thread must succeed with $100 withdrawal.
        assertEquals(1, successCount.get(), "Exactly one withdrawal of $100 should succeed on a $100 balance");
        assertEquals(9, insufficientFundsCount.get(), "Nine concurrent withdrawal requests should fail with InsufficientFundsException");

        Account finalAccountState = accountRepository.findById(testAccount.getId()).orElseThrow();
        assertEquals(0, finalAccountState.getBalance().compareTo(new BigDecimal("0.0000")),
                "Final balance must be exactly $0.00 and NEVER negative");
    }
}
