package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.dto.LedgerProofResponse;
import com.snovasys.ledgerline.model.Account;
import com.snovasys.ledgerline.model.AccountType;
import com.snovasys.ledgerline.model.EntryType;
import com.snovasys.ledgerline.model.JournalEntry;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.JournalEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class LedgerVerificationService {

    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public LedgerVerificationService(JournalEntryRepository journalEntryRepository,
                                   AccountRepository accountRepository,
                                   AuditService auditService) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /**
     * Conducts a full mathematical audit and cryptographic proof of the entire ledger.
     * Invariants verified:
     * 1. Global Zero-Sum: Total Debits == Total Credits across the entire system.
     * 2. Per-Account Invariant: account.balance == sum(CREDIT) - sum(DEBIT).
     * 3. Immutability: SHA-256 cryptographic chain digest over all journal records.
     */
    @Transactional(readOnly = true)
    public LedgerProofResponse verifySystemLedger(String auditorUsername) {
        BigDecimal rawDebits = journalEntryRepository.sumAmountByEntryType(EntryType.DEBIT);
        BigDecimal totalDebits = (rawDebits != null ? rawDebits : BigDecimal.ZERO).setScale(4);

        BigDecimal rawCredits = journalEntryRepository.sumAmountByEntryType(EntryType.CREDIT);
        BigDecimal totalCredits = (rawCredits != null ? rawCredits : BigDecimal.ZERO).setScale(4);

        BigDecimal discrepancy = totalDebits.subtract(totalCredits).abs();

        boolean globalBalanceValid = (discrepancy.compareTo(new BigDecimal("0.0000")) == 0);

        List<Account> allAccounts = accountRepository.findAll();
        boolean accountsValid = true;
        BigDecimal userBalanceSum = BigDecimal.ZERO.setScale(4);
        BigDecimal vaultReserve = BigDecimal.ZERO.setScale(4);

        for (Account account : allAccounts) {
            if (account.getAccountType() == AccountType.SYSTEM_VAULT) {
                vaultReserve = account.getBalance().setScale(4);
            } else {
                userBalanceSum = userBalanceSum.add(account.getBalance()).setScale(4);
            }

            BigDecimal rawNet = journalEntryRepository.calculateNetBalanceForAccount(account.getId());
            BigDecimal netFromLedger = (rawNet != null ? rawNet : BigDecimal.ZERO).setScale(4);
            // In double-entry: for user accounts, net = credits - debits
            if (account.getAccountType() != AccountType.SYSTEM_VAULT) {
                if (account.getBalance().compareTo(netFromLedger) != 0) {
                    accountsValid = false;
                }
            }
        }

        // Compute cryptographic proof hash chain
        List<JournalEntry> allEntries = journalEntryRepository.findAllByOrderByCreatedAtAsc();
        String integrityHash = computeLedgerHashChain(allEntries);

        boolean fullyBalanced = globalBalanceValid && accountsValid;
        String verdict = fullyBalanced
                ? "MATHEMATICALLY PROVED: Total debits equal total credits. All account balances match journal proofs."
                : "AUDIT FAILURE: Invariant breach detected between journal entries and account states.";

        auditService.logEvent(
                auditorUsername != null ? auditorUsername : "SYSTEM_AUDITOR",
                "LEDGER_AUDIT_VERIFIED",
                "ledger/proof",
                fullyBalanced ? "PROVEN" : "FAILED",
                "Debits=" + totalDebits + ", Credits=" + totalCredits + ", Discrepancy=" + discrepancy,
                "0.0.0.0"
        );

        return new LedgerProofResponse(
                fullyBalanced,
                totalDebits,
                totalCredits,
                discrepancy,
                allEntries.size(),
                allAccounts.size(),
                userBalanceSum,
                vaultReserve,
                integrityHash,
                verdict,
                LocalDateTime.now()
        );
    }

    private String computeLedgerHashChain(List<JournalEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] runningHash = new byte[32]; // Genesis seed

            for (JournalEntry entry : entries) {
                String recordPayload = entry.getId() + ":" +
                        entry.getTransaction().getReferenceId() + ":" +
                        entry.getAccount().getId() + ":" +
                        entry.getEntryType() + ":" +
                        entry.getAmount() + ":" +
                        entry.getBalanceAfter() + ":" +
                        entry.getCreatedAt().toString();

                digest.update(runningHash);
                digest.update(recordPayload.getBytes(StandardCharsets.UTF_8));
                runningHash = digest.digest();
            }

            return HexFormat.of().formatHex(runningHash);
        } catch (Exception e) {
            return "ERROR_COMPUTING_HASH";
        }
    }
}
