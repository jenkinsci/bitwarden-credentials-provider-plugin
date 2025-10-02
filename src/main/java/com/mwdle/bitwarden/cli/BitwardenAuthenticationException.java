package com.mwdle.bitwarden.cli;

/**
 * A specialized IOException thrown when the Bitwarden CLI fails due to an authentication error.
 */
public class BitwardenAuthenticationException extends RuntimeException {
    public BitwardenAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
