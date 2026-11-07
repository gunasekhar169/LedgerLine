package com.snovasys.ledgerline.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferRequest(
    @NotNull(message = "Source account ID is required")
    Long fromAccountId,

    @NotNull(message = "Destination account ID is required")
    Long toAccountId,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    BigDecimal amount,

    String description
) {}
