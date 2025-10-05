package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import hudson.util.Secret;

/**
 * Represents the nested {@code login} object within a Bitwarden item JSON,
 * containing the username and password fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitwardenLogin {
    /**
     * The username associated with the login.
     */
    @JsonDeserialize(using = SecretDeserializer.class)
    private Secret username;
    /**
     * The password associated with the login.
     */
    @JsonDeserialize(using = SecretDeserializer.class)
    private Secret password;

    /**
     * Gets the username for this login.
     *
     * @return The username as a {@link Secret}.
     */
    public Secret getUsername() {
        return username;
    }

    /**
     * Gets the password for this login.
     *
     * @return The password as a {@link Secret}.
     */
    public Secret getPassword() {
        return password;
    }
}
