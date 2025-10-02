package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import hudson.util.Secret;

/**
 * Represents the nested 'login' object within a Bitwarden item JSON.
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
     * @return The username for this login.
     */
    public Secret getUsername() {
        return username;
    }

    /**
     * @return The password for this login.
     */
    public Secret getPassword() {
        return password;
    }
}
