package com.mwdle.bitwarden.cli;

/**
 * A specialized {@link RuntimeException} thrown when the Bitwarden CLI fails due to an
 * authentication error, such as an incorrect API key or master password.
 */
public final class BitwardenAuthenticationException extends RuntimeException {
    /**
     * Constructs a new BitwardenAuthenticationException.
     *
     * @param message The detail message explaining why authentication failed, which should be a user-friendly, internationalized string.
     * @param cause   The low-level exception that caused this failure (e.g., the original IOException from the CLI).
     */
    public BitwardenAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
