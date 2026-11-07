package com.snovasys.ledgerline.exception;

public class AccountFrozenException extends RuntimeException {
    public AccountFrozenException(String message) {
        super(message);
    }
}
