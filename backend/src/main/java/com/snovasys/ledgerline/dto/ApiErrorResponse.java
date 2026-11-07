package com.snovasys.ledgerline.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ApiErrorResponse(
    int status,
    String error,
    String message,
    String path,
    Map<String, String> validationErrors,
    LocalDateTime timestamp
) {
    public ApiErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, null, LocalDateTime.now());
    }

    public ApiErrorResponse(int status, String error, String message, String path, Map<String, String> validationErrors) {
        this(status, error, message, path, validationErrors, LocalDateTime.now());
    }
}
