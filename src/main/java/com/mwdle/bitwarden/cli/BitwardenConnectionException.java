package com.mwdle.bitwarden.cli;

import java.io.IOException;

/**
 * A specialized {@link IOException} thrown when the Bitwarden CLI fails due to a
 * network-related issue, such as a DNS failure or an inability to connect to the server.
 */
public class BitwardenConnectionException extends IOException {
    /**
     * Constructs a new BitwardenConnectionException.
     *
     * @param message The detail message, which should be a user-friendly, internationalized string.
     * @param cause   The low-level exception that caused this failure (e.g., the original IOException from the CLI).
     */
    public BitwardenConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
