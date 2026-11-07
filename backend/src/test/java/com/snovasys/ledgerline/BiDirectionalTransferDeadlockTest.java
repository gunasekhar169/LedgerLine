package com.snovasys.ledgerline;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransferRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class BiDirectionalTransferDeadlockTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private Account accountA;
    private Account accountB;
    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = userRepository.findByUsername("admin").orElseGet(() -> {
            User u = new User("admin", "admin@ledgerline.io", "hash", "Admin", Role.ROLE_ADMIN);
            return userRepository.save(u);
        });

        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("admin", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        accountA = accountRepository.save(new Account("DEADLOCK-TEST-A", adminUser, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));
        accountB = accountRepository.save(new Account("DEADLOCK-TEST-B", adminUser, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD"));

        ledgerService.deposit(new DepositRequest(accountA.getId(), new BigDecimal("500.00"), "Deposit A"), null, "127.0.0.1");
        ledgerService.deposit(new DepositRequest(accountB.getId(), new BigDecimal("500.00"), "Deposit B"), null, "127.0.0.1");
    }

    @Test
    @DisplayName("Threat Test: Concurrent Bi-Directional Transfers (A->B and B->A) must not cause Deadlocks")
    void testBiDirectionalTransfersPreventDeadlock() throws InterruptedException {
        int rounds = 20;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(rounds * 2);
        AtomicInteger completedCount = new AtomicInteger(0);

        for (int i = 0; i < rounds; i++) {
            executor.submit(() -> {
                setAdminAuth();
                try {
                    ledgerService.transfer(new TransferRequest(accountA.getId(), accountB.getId(), new BigDecimal("10.00"), "A to B"), null, "127.0.0.1");
                    completedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                setAdminAuth();
                try {
                    ledgerService.transfer(new TransferRequest(accountB.getId(), accountA.getId(), new BigDecimal("10.00"), "B to A"), null, "127.0.0.1");
                    completedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(rounds * 2, completedCount.get(), "All bi-directional transfers should complete cleanly without deadlocks");
    }

    private void setAdminAuth() {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User("admin", "hash",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }
}
