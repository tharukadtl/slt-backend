package lk.slt.fieldops.shared.exception;

/** ANA-010 — a report date range whose end precedes its start. */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
