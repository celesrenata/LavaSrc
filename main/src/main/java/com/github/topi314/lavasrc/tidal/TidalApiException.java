package com.github.topi314.lavasrc.tidal;

/**
 * Exception thrown when a Tidal v2 API request fails after all retry attempts are exhausted.
 */
public class TidalApiException extends RuntimeException {

    private final int statusCode;

    public TidalApiException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public TidalApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public TidalApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public TidalApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Returns the HTTP status code that caused the failure, or -1 if not applicable.
     */
    public int getStatusCode() {
        return statusCode;
    }
}
