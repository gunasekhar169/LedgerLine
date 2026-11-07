package com.snovasys.ledgerline.exception;

public class LedgerUnbalancedException extends RuntimeException {
    public LedgerUnbalancedException(String message) {
        super(message);
    }
}
