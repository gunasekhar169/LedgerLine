package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.EntryType;
import com.snovasys.ledgerline.model.JournalEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record JournalEntryResponse(
    Long id,
    Long transactionId,
    String transactionReference,
    Long accountId,
    String accountNumber,
    EntryType entryType,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String description,
    LocalDateTime createdAt
) {
    public static JournalEntryResponse fromEntity(JournalEntry entry) {
        return new JournalEntryResponse(
            entry.getId(),
            entry.getTransaction().getId(),
            entry.getTransaction().getReferenceId(),
            entry.getAccount().getId(),
            entry.getAccount().getAccountNumber(),
            entry.getEntryType(),
            entry.getAmount(),
            entry.getBalanceAfter(),
            entry.getDescription(),
            entry.getCreatedAt()
        );
    }
}
