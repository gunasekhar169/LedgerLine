package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.Account;
import com.snovasys.ledgerline.model.AccountStatus;
import com.snovasys.ledgerline.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountResponse(
    Long id,
    String accountNumber,
    Long userId,
    String ownerName,
    AccountType accountType,
    BigDecimal balance,
    String currency,
    AccountStatus status,
    LocalDateTime createdAt
) {
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
            account.getId(),
            account.getAccountNumber(),
            account.getUser() != null ? account.getUser().getId() : null,
            account.getUser() != null ? account.getUser().getFullName() : "SYSTEM",
            account.getAccountType(),
            account.getBalance(),
            account.getCurrency(),
            account.getStatus(),
            account.getCreatedAt()
        );
    }
}
