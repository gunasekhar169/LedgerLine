package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.model.*;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final LedgerService ledgerService;

    public DataInitializer(UserRepository userRepository,
                           AccountRepository accountRepository,
                           PasswordEncoder passwordEncoder,
                           LedgerService ledgerService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.ledgerService = ledgerService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already seeded. Skipping initial data provisioning.");
            return;
        }

        log.info("Seeding initial LedgerLine users, accounts, and balanced ledger...");

        // 1. Create System Vault Account
        Account systemVault = new Account("SYSTEM-VAULT-RESERVE", null, AccountType.SYSTEM_VAULT, BigDecimal.ZERO, "USD");
        accountRepository.save(systemVault);

        // 2. Admin User
        User admin = new User("admin", "admin@ledgerline.io", passwordEncoder.encode("admin123"), "System Administrator", Role.ROLE_ADMIN);
        userRepository.save(admin);

        // 3. Auditor User
        User auditor = new User("auditor", "auditor@ledgerline.io", passwordEncoder.encode("auditor123"), "Compliance Auditor", Role.ROLE_AUDITOR);
        userRepository.save(auditor);

        // 4. Regular Users (Alice and Bob)
        User alice = new User("alice", "alice@example.com", passwordEncoder.encode("password123"), "Alice Smith", Role.ROLE_ACCOUNT_HOLDER);
        userRepository.save(alice);

        User bob = new User("bob", "bob@example.com", passwordEncoder.encode("password123"), "Bob Jones", Role.ROLE_ACCOUNT_HOLDER);
        userRepository.save(bob);

        // 5. User Accounts
        Account aliceChecking = new Account("LL-1001-2001", alice, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD");
        Account aliceSavings = new Account("LL-1001-2002", alice, AccountType.USER_SAVINGS, BigDecimal.ZERO, "USD");
        Account bobChecking = new Account("LL-1002-3001", bob, AccountType.USER_CHECKING, BigDecimal.ZERO, "USD");

        accountRepository.save(aliceChecking);
        accountRepository.save(aliceSavings);
        accountRepository.save(bobChecking);

        log.info("LedgerLine seed accounts created successfully.");
    }
}
