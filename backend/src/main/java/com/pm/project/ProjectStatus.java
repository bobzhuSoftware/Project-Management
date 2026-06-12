package com.pm.project;

public enum ProjectStatus {
    /** Managed by this process: started via API and Process handle is alive. */
    RUNNING,
    /** Was started via API; this PM was restarted; PID still alive — re-attached but logs lost. */
    ATTACHED,
    /** No managed PID, but at least one declared port is currently listening (likely started outside PM). */
    EXTERNAL,
    /** No managed PID and no declared port is listening. */
    STOPPED,
    /** Last start attempt failed. */
    ERROR
}
