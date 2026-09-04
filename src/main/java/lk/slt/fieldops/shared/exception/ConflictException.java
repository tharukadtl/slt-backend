package lk.slt.fieldops.shared.exception;

/** Generic 409 — a state conflict that isn't a duplicate day-session (see DuplicateSessionException). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
