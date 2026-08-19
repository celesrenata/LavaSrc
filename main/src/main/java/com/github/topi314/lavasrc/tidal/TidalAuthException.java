package com.github.topi314.lavasrc.tidal;

/**
 * Exception thrown when Tidal OAuth token acquisition fails after all retry attempts.
 */
public class TidalAuthException extends RuntimeException {

    public TidalAuthException(String message) {
        super(message);
    }

    public TidalAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
