package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.model.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AccountCreateRequest(
    @NotNull(message = "Account type is required")
    AccountType accountType,

    @DecimalMin(value = "0.00", message = "Initial balance cannot be negative")
    BigDecimal initialBalance,

    String currency
) {}
