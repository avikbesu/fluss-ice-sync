package com.flusssync.process;

/** Mirrors the design doc's File Lifecycle state diagram. */
public enum FileState {
    DETECTED,
    VALIDATING,
    STREAMING,
    FAILED_RETRYING,
    PROCESSED,
    ARCHIVED,
    DELETED,
    REJECTED,
    SKIPPED
}
