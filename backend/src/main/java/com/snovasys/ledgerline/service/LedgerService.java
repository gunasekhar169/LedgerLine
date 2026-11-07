package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.dto.*;
import com.snovasys.ledgerline.exception.*;
import com.snovasys.ledgerline.model.*;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.JournalEntryRepository;
import com.snovasys.ledgerline.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;

    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         JournalEntryRepository journalEntryRepository,
                         AuthService authService,
                         AuditService auditService,
                         IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.authService = authService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Executes an internal transfer between two accounts.
     * Features:
     * - Deadlock-free pessimistic row locking (locks accounts in ascending ID order).
     * - Server-side negative balance prevention.
     * - Ownership verification (IDOR protection).
     * - Atomic double-entry balancing (Debit = Credit).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transfer(TransferRequest req, String idempotencyKey, String clientIp) {
        BigDecimal amount = req.amount().setScale(4);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be strictly greater than zero");
        }

        if (req.fromAccountId().equals(req.toAccountId())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account");
        }

        // 1. Deadlock-free lock acquisition order: always lock lower ID first
        Long firstId = Math.min(req.fromAccountId(), req.toAccountId());
        Long secondId = Math.max(req.fromAccountId(), req.toAccountId());

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException("Account ID " + firstId + " not found"));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException("Account ID " + secondId + " not found"));

        Account fromAccount = req.fromAccountId().equals(firstId) ? first : second;
        Account toAccount = req.toAccountId().equals(firstId) ? first : second;

        // 2. Ownership verification (prevents IDOR)
        User currentUser = authService.getCurrentUser();
        boolean isOwner = fromAccount.getUser() != null && fromAccount.getUser().getId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ROLE_ADMIN;

        if (!isOwner && !isAdmin) {
            auditService.logEvent(currentUser.getUsername(), "IDOR_TRANSFER_BLOCKED", "accounts/" + fromAccount.getId(), "BLOCKED",
                    "User tried to debit unowned account " + fromAccount.getAccountNumber(), clientIp);
            throw new UnauthorizedAccountAccessException("Access denied: You do not have permission to debit account " + fromAccount.getId());
        }

        // 3. Status checks
        if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException("Source account " + fromAccount.getAccountNumber() + " is " + fromAccount.getStatus());
        }
        if (toAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException("Destination account " + toAccount.getAccountNumber() + " is " + toAccount.getStatus());
        }

        // 4. Invariant check: balance cannot become negative
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            auditService.logEvent(currentUser.getUsername(), "TRANSFER_FAILED_INSUFFICIENT_FUNDS",
                    "accounts/" + fromAccount.getId(), "REJECTED",
                    "Available: " + fromAccount.getBalance() + ", Requested: " + amount, clientIp);
            throw new InsufficientFundsException("Insufficient funds. Available balance: " + fromAccount.getBalance() + ", Required: " + amount);
        }

        // 5. Update balances
        BigDecimal newFromBalance = fromAccount.getBalance().subtract(amount);
        BigDecimal newToBalance = toAccount.getBalance().add(amount);

        fromAccount.setBalance(newFromBalance);
        toAccount.setBalance(newToBalance);

        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // 6. Record transaction
        String desc = req.description() != null && !req.description().isBlank() ? req.description() : "Transfer between accounts";
        Transaction tx = new Transaction(
                TransactionType.TRANSFER,
                amount,
                fromAccount,
                toAccount,
                TransactionStatus.POSTED,
                desc,
                currentUser,
                idempotencyKey
        );
        tx = transactionRepository.save(tx);

        // 7. Double-entry balanced journal entries
        JournalEntry debitEntry = new JournalEntry(
                tx, fromAccount, EntryType.DEBIT, amount, newFromBalance, "Transfer debit to " + toAccount.getAccountNumber()
        );
        JournalEntry creditEntry = new JournalEntry(
                tx, toAccount, EntryType.CREDIT, amount, newToBalance, "Transfer credit from " + fromAccount.getAccountNumber()
        );

        journalEntryRepository.save(debitEntry);
        journalEntryRepository.save(creditEntry);

        // 8. Strict mathematical conservation check
        if (debitEntry.getAmount().compareTo(creditEntry.getAmount()) != 0) {
            throw new LedgerUnbalancedException("FATAL: Debits and credits do not balance in transaction " + tx.getReferenceId());
        }

        auditService.logEvent(currentUser.getUsername(), "TRANSFER_SUCCESS", "transactions/" + tx.getReferenceId(), "SUCCESS",
                "Transferred " + amount + " from " + fromAccount.getAccountNumber() + " to " + toAccount.getAccountNumber(), clientIp);

        TransactionResponse response = TransactionResponse.fromEntity(tx);

        // Record idempotency
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            idempotencyService.recordSuccess(idempotencyKey, currentUser.getId(), hash, 200, response);
        }

        return response;
    }

    /**
     * Deposits money into an account from the system cash reserve.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse deposit(DepositRequest req, String idempotencyKey, String clientIp) {
        BigDecimal amount = req.amount().setScale(4);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be strictly greater than zero");
        }

        User currentUser = authService.getCurrentUser();
        Account targetAccount = accountRepository.findByIdForUpdate(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException("Account ID " + req.accountId() + " not found"));

        if (targetAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException("Account " + targetAccount.getAccountNumber() + " is " + targetAccount.getStatus());
        }

        Account systemVault = getOrCreateSystemVault();

        // Update balances
        BigDecimal newTargetBalance = targetAccount.getBalance().add(amount);
        BigDecimal newVaultBalance = systemVault.getBalance().add(amount);

        targetAccount.setBalance(newTargetBalance);
        systemVault.setBalance(newVaultBalance);

        accountRepository.save(targetAccount);
        accountRepository.save(systemVault);

        String desc = req.description() != null && !req.description().isBlank() ? req.description() : "Deposit from external source";
        Transaction tx = new Transaction(
                TransactionType.DEPOSIT,
                amount,
                systemVault,
                targetAccount,
                TransactionStatus.POSTED,
                desc,
                currentUser,
                idempotencyKey
        );
        tx = transactionRepository.save(tx);

        // In double-entry: DEBIT Vault Reserve (Asset increased), CREDIT User Account (Liability increased)
        JournalEntry debitEntry = new JournalEntry(
                tx, systemVault, EntryType.DEBIT, amount, newVaultBalance, "Cash deposit received into system vault"
        );
        JournalEntry creditEntry = new JournalEntry(
                tx, targetAccount, EntryType.CREDIT, amount, newTargetBalance, "Deposit credited to account " + targetAccount.getAccountNumber()
        );

        journalEntryRepository.save(debitEntry);
        journalEntryRepository.save(creditEntry);

        auditService.logEvent(currentUser.getUsername(), "DEPOSIT_SUCCESS", "transactions/" + tx.getReferenceId(), "SUCCESS",
                "Deposited " + amount + " to " + targetAccount.getAccountNumber(), clientIp);

        TransactionResponse response = TransactionResponse.fromEntity(tx);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            idempotencyService.recordSuccess(idempotencyKey, currentUser.getId(), hash, 200, response);
        }

        return response;
    }

    /**
     * Withdraws money from an account to an external destination.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse withdraw(WithdrawalRequest req, String idempotencyKey, String clientIp) {
        BigDecimal amount = req.amount().setScale(4);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be strictly greater than zero");
        }

        User currentUser = authService.getCurrentUser();
        Account targetAccount = accountRepository.findByIdForUpdate(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException("Account ID " + req.accountId() + " not found"));

        boolean isOwner = targetAccount.getUser() != null && targetAccount.getUser().getId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole() == Role.ROLE_ADMIN;

        if (!isOwner && !isAdmin) {
            auditService.logEvent(currentUser.getUsername(), "IDOR_WITHDRAW_BLOCKED", "accounts/" + targetAccount.getId(), "BLOCKED",
                    "Unauthorized withdrawal attempt on " + targetAccount.getAccountNumber(), clientIp);
            throw new UnauthorizedAccountAccessException("Access denied: You do not own account " + req.accountId());
        }

        if (targetAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountFrozenException("Account " + targetAccount.getAccountNumber() + " is " + targetAccount.getStatus());
        }

        if (targetAccount.getBalance().compareTo(amount) < 0) {
            auditService.logEvent(currentUser.getUsername(), "WITHDRAWAL_FAILED_INSUFFICIENT_FUNDS",
                    "accounts/" + targetAccount.getId(), "REJECTED",
                    "Available: " + targetAccount.getBalance() + ", Requested: " + amount, clientIp);
            throw new InsufficientFundsException("Insufficient funds. Available: " + targetAccount.getBalance() + ", Requested: " + amount);
        }

        Account systemVault = getOrCreateSystemVault();

        // Update balances
        BigDecimal newTargetBalance = targetAccount.getBalance().subtract(amount);
        BigDecimal newVaultBalance = systemVault.getBalance().subtract(amount);

        targetAccount.setBalance(newTargetBalance);
        systemVault.setBalance(newVaultBalance);

        accountRepository.save(targetAccount);
        accountRepository.save(systemVault);

        String desc = req.description() != null && !req.description().isBlank() ? req.description() : "Withdrawal to external destination";
        Transaction tx = new Transaction(
                TransactionType.WITHDRAWAL,
                amount,
                targetAccount,
                systemVault,
                TransactionStatus.POSTED,
                desc,
                currentUser,
                idempotencyKey
        );
        tx = transactionRepository.save(tx);

        // In double-entry: DEBIT User Account, CREDIT Vault Reserve
        JournalEntry debitEntry = new JournalEntry(
                tx, targetAccount, EntryType.DEBIT, amount, newTargetBalance, "Withdrawal debited from account " + targetAccount.getAccountNumber()
        );
        JournalEntry creditEntry = new JournalEntry(
                tx, systemVault, EntryType.CREDIT, amount, newVaultBalance, "Cash paid out from system vault"
        );

        journalEntryRepository.save(debitEntry);
        journalEntryRepository.save(creditEntry);

        auditService.logEvent(currentUser.getUsername(), "WITHDRAWAL_SUCCESS", "transactions/" + tx.getReferenceId(), "SUCCESS",
                "Withdrew " + amount + " from " + targetAccount.getAccountNumber(), clientIp);

        TransactionResponse response = TransactionResponse.fromEntity(tx);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = idempotencyService.computePayloadHash(req);
            idempotencyService.recordSuccess(idempotencyKey, currentUser.getId(), hash, 200, response);
        }

        return response;
    }

    @Transactional(readOnly = true)
    public StatementResponse getAccountStatement(Long accountId) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account ID " + accountId + " not found"));

        boolean isOwner = account.getUser() != null && account.getUser().getId().equals(currentUser.getId());
        boolean isPrivileged = currentUser.getRole() == Role.ROLE_ADMIN || currentUser.getRole() == Role.ROLE_AUDITOR;

        if (!isOwner && !isPrivileged) {
            throw new UnauthorizedAccountAccessException("Access denied: You do not own account " + accountId);
        }

        List<JournalEntryResponse> entries = journalEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(JournalEntryResponse::fromEntity)
                .toList();

        return new StatementResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getUser() != null ? account.getUser().getFullName() : "SYSTEM",
                account.getBalance(),
                account.getCurrency(),
                entries
        );
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getTransactionsForAccount(Long accountId) {
        // IDOR check
        getAccountStatement(accountId);
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(TransactionResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getAllTransactions() {
        User currentUser = authService.getCurrentUser();
        if (currentUser.getRole() != Role.ROLE_ADMIN && currentUser.getRole() != Role.ROLE_AUDITOR) {
            throw new UnauthorizedAccountAccessException("Only administrators and auditors can view the global transaction log");
        }
        return transactionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(TransactionResponse::fromEntity)
                .toList();
    }

    private Account getOrCreateSystemVault() {
        return accountRepository.findByAccountType(AccountType.SYSTEM_VAULT)
                .orElseGet(() -> {
                    Account vault = new Account("SYSTEM-VAULT-RESERVE", null, AccountType.SYSTEM_VAULT, BigDecimal.ZERO, "USD");
                    return accountRepository.save(vault);
                });
    }
}
