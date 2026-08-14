package com.mwdle.bitwarden.cli;

import java.io.IOException;

/**
 * A specialized {@link IOException} thrown when the Bitwarden CLI fails due to a
 * network-related issue, such as a DNS failure or an inability to connect to the server.
 */
public final class ConnectionException extends IOException {

    public static final String IDENTIFIER = "FetchError";

    /**
     * Constructs a new BitwardenConnectionException.
     *
     * @param message The detail message explaining why the connection failed, which should be a user-friendly, internationalized string.
     * @param cause   The low-level exception that caused this failure (e.g., the original IOException from the CLI).
     */
    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
