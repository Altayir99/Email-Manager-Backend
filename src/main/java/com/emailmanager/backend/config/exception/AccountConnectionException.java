package com.emailmanager.backend.config.exception;

public class AccountConnectionException extends RuntimeException {
    public AccountConnectionException(String message) {
        super(message);
    }

    public AccountConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
