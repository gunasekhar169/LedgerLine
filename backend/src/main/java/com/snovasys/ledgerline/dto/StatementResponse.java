package com.snovasys.ledgerline.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatementResponse(
    Long accountId,
    String accountNumber,
    String ownerName,
    BigDecimal currentBalance,
    String currency,
    List<JournalEntryResponse> entries
) {}
