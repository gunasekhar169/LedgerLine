package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.dto.AccountCreateRequest;
import com.snovasys.ledgerline.dto.AccountResponse;
import com.snovasys.ledgerline.exception.AccountNotFoundException;
import com.snovasys.ledgerline.exception.UnauthorizedAccountAccessException;
import com.snovasys.ledgerline.model.*;
import com.snovasys.ledgerline.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AccountService(AccountRepository accountRepository, AuthService authService, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public AccountResponse createAccount(AccountCreateRequest req) {
        User currentUser = authService.getCurrentUser();
        String accountNumber = generateUniqueAccountNumber();

        Account account = new Account(
                accountNumber,
                currentUser,
                req.accountType() != null ? req.accountType() : AccountType.USER_CHECKING,
                BigDecimal.ZERO, // All balance increases must happen through double-entry ledger transactions!
                req.currency() != null ? req.currency().toUpperCase() : "USD"
        );

        Account saved = accountRepository.save(account);
        auditService.logEvent(currentUser.getUsername(), "ACCOUNT_CREATED", "accounts/" + saved.getId(), "SUCCESS",
                "Account: " + saved.getAccountNumber() + ", Type: " + saved.getAccountType(), "0.0.0.0");

        return AccountResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Account getAccountEntityWithOwnershipCheck(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found with ID: " + accountId));

        User currentUser = authService.getCurrentUser();
        boolean isOwner = account.getUser() != null && account.getUser().getId().equals(currentUser.getId());
        boolean isPrivileged = currentUser.getRole() == Role.ROLE_ADMIN || currentUser.getRole() == Role.ROLE_AUDITOR;

        if (!isOwner && !isPrivileged) {
            auditService.logEvent(currentUser.getUsername(), "IDOR_ATTEMPT_ACCOUNT_VIEW", "accounts/" + accountId, "BLOCKED",
                    "Unauthorized attempt to inspect account: " + accountId, "0.0.0.0");
            throw new UnauthorizedAccountAccessException("Access denied: You do not own account ID " + accountId);
        }

        return account;
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long accountId) {
        Account account = getAccountEntityWithOwnershipCheck(accountId);
        return AccountResponse.fromEntity(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts() {
        User currentUser = authService.getCurrentUser();
        return accountRepository.findByUserId(currentUser.getId()).stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll().stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional
    public AccountResponse updateAccountStatus(Long accountId, AccountStatus newStatus) {
        User currentUser = authService.getCurrentUser();
        if (currentUser.getRole() != Role.ROLE_ADMIN) {
            throw new UnauthorizedAccountAccessException("Only administrators can change account status");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found with ID: " + accountId));

        account.setStatus(newStatus);
        Account saved = accountRepository.save(account);

        auditService.logEvent(currentUser.getUsername(), "ACCOUNT_STATUS_CHANGED", "accounts/" + accountId, "SUCCESS",
                "Status changed to: " + newStatus, "0.0.0.0");

        return AccountResponse.fromEntity(saved);
    }

    private String generateUniqueAccountNumber() {
        String accNum;
        do {
            int part1 = 1000 + RANDOM.nextInt(9000);
            int part2 = 1000 + RANDOM.nextInt(9000);
            accNum = "LL-" + part1 + "-" + part2;
        } while (accountRepository.findByAccountNumber(accNum).isPresent());
        return accNum;
    }
}
