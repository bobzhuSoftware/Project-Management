package com.pm.process;

/** A synchronous project command exited with a non-zero code. Still an {@link IllegalStateException} so it maps to HTTP 409. */
public class CommandFailedException extends IllegalStateException {

    private final int exitCode;

    public CommandFailedException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
