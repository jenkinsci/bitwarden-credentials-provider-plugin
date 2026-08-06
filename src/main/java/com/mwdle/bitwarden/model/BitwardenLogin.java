package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;

/**
 * The login credentials (username and password) of a Bitwarden item.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitwardenLogin(
        @CheckForNull @JsonDeserialize(using = SecretDeserializer.class)
        Secret username,

        @CheckForNull @JsonDeserialize(using = SecretDeserializer.class)
        Secret password) {}
