package com.pm.process;

/** A synchronous project command was stopped by the user. Still an {@link IllegalStateException} so it maps to HTTP 409. */
public class CommandCancelledException extends IllegalStateException {

    public CommandCancelledException(String message) {
        super(message);
    }
}
