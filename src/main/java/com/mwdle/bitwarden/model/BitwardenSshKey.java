package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

/**
 * Represents the nested 'sshKey' object within a Bitwarden item JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// Suppress SpotBugs warning for fields populated by the Jackson JSON parser
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
     * @return The private key text.
     */
    public Secret getPrivateKey() {
        return privateKey;
    }

    /**
     * @return The public key text.
     */
    public String getPublicKey() {
        return publicKey;
    }
}
