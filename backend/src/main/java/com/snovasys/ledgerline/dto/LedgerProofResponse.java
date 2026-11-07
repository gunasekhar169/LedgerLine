package com.snovasys.ledgerline.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LedgerProofResponse(
    boolean isBalanced,
    BigDecimal totalDebits,
    BigDecimal totalCredits,
    BigDecimal netDiscrepancy,
    long totalJournalEntries,
    long totalAccountsVerified,
    BigDecimal totalUserDeposits,
    BigDecimal systemVaultReserve,
    String ledgerIntegrityHash,
    String proofVerdict,
    LocalDateTime auditTimestamp
) {}
