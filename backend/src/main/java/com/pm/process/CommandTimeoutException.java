package com.pm.process;

/** A synchronous project command exceeded its timeout and was killed. Still an {@link IllegalStateException} so it maps to HTTP 409. */
public class CommandTimeoutException extends IllegalStateException {

    public CommandTimeoutException(String message) {
        super(message);
    }
}
