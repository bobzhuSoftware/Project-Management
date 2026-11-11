package com.pm.build;

import java.time.Instant;

/** Whether a launch's build output matches the project's current HEAD. */
public class BuildFreshnessDto {
    public static final String FRESH = "FRESH";
    public static final String STALE = "STALE";
    public static final String NO_RECORD = "NO_RECORD";
    public static final String BUILDING = "BUILDING";

    /** FRESH / STALE / NO_RECORD / BUILDING. */
    public String state;
    /** Only for STALE: commits between the built commit and HEAD; -1 when they are not on the same history line. */
    public Integer commitsBehind;
    /** Current HEAD (short SHA). */
    public String headSha;
    /** Commit of the oldest last-successful build across the launch's build commands (short SHA). */
    public String builtSha;
    public String builtSubject;
    public Instant builtAt;
    /** Status of the most recent build record, so the UI can flag "last build failed". */
    public String lastResult;
}
