package lk.slt.fieldops.shared.exception;

/**
 * Thrown when a refresh token is presented that can no longer be exchanged — it has expired or has
 * already been consumed (rotated/revoked). This is an authentication failure, not a malformed
 * request, so {@code GlobalExceptionHandler} maps it to HTTP 401 rather than the generic 400.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
