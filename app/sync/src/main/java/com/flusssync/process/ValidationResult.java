package com.flusssync.process;

import java.util.List;

/**
 * Outcome of {@link Validator#validate}. {@code materializedRows} is
 * populated only in {@code FULL} validation mode, letting {@code STREAMING}
 * reuse the already-parsed rows instead of re-reading the file (see the
 * design doc's Streaming-into-Fluss section).
 */
public final class ValidationResult {

    private final boolean valid;
    private final String reason;
    private final List<Row> materializedRows;

    private ValidationResult(boolean valid, String reason, List<Row> materializedRows) {
        this.valid = valid;
        this.reason = reason;
        this.materializedRows = materializedRows;
    }

    public static ValidationResult valid(List<Row> materializedRows) {
        return new ValidationResult(true, null, materializedRows);
    }

    public static ValidationResult rejected(String reason) {
        return new ValidationResult(false, reason, null);
    }

    public boolean isValid() {
        return valid;
    }

    public String reason() {
        return reason;
    }

    /** Non-null only when validation ran in FULL mode and succeeded. */
    public List<Row> materializedRows() {
        return materializedRows;
    }
}
