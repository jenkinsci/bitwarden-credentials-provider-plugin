package com.mwdle.bitwarden;

import java.io.IOException;

/**
 * A specialized IOException thrown when the Bitwarden CLI fails due to a network-related issue.
 */
public class BitwardenConnectionException extends IOException {
    public BitwardenConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
