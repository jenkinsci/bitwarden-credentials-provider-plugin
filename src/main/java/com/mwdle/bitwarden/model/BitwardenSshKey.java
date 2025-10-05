package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

/**
 * Represents the nested {@code sshKey} object within a Bitwarden item JSON,
 * containing the private and public key fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
public class BitwardenSshKey {
    /**
     * The private key text.
     */
    @JsonDeserialize(using = SecretDeserializer.class)
    private Secret privateKey;
    /**
     * The public key text, which may include a comment.
     */
    private String publicKey;

    /**
     * Gets the private key text.
     *
     * @return The private key as a {@link Secret}.
     */
    public Secret getPrivateKey() {
        return privateKey;
    }

    /**
     * Gets the public key text.
     *
     * @return The public key text.
     */
    public String getPublicKey() {
        return publicKey;
    }
}
