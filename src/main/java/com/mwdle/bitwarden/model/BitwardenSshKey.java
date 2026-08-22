package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;

/**
 * The SSH key data (private and public keys) of a Bitwarden item.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BitwardenSshKey(
        @CheckForNull @JsonDeserialize(using = SecretDeserializer.class)
        Secret privateKey,

        @CheckForNull @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        String publicKey) {}
