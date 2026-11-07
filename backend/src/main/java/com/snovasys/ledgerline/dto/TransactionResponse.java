package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.Transaction;
import com.snovasys.ledgerline.model.TransactionStatus;
import com.snovasys.ledgerline.model.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
    Long id,
    String referenceId,
    String idempotencyKey,
    TransactionType type,
    BigDecimal amount,
    Long sourceAccountId,
    String sourceAccountNumber,
    Long destinationAccountId,
    String destinationAccountNumber,
    TransactionStatus status,
    String description,
    String initiatedByUsername,
    LocalDateTime createdAt
) {
    public static TransactionResponse fromEntity(Transaction tx) {
        return new TransactionResponse(
            tx.getId(),
            tx.getReferenceId(),
            tx.getIdempotencyKey(),
            tx.getType(),
            tx.getAmount(),
            tx.getSourceAccount() != null ? tx.getSourceAccount().getId() : null,
            tx.getSourceAccount() != null ? tx.getSourceAccount().getAccountNumber() : null,
            tx.getDestinationAccount() != null ? tx.getDestinationAccount().getId() : null,
            tx.getDestinationAccount() != null ? tx.getDestinationAccount().getAccountNumber() : null,
            tx.getStatus(),
            tx.getDescription(),
            tx.getInitiatedBy() != null ? tx.getInitiatedBy().getUsername() : "SYSTEM",
            tx.getCreatedAt()
        );
    }
}
