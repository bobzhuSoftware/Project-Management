package com.pm.build;

public enum BuildStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED,
    /** Stopped by the user while running. */
    CANCELLED,
    /** Killed because it exceeded its configured timeout. */
    TIMED_OUT
}
