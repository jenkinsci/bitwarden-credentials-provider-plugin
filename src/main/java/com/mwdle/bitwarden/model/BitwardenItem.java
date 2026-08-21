package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;

/**
 * A concrete Bitwarden item, including secrets, deserialized from {@code bw get item}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BitwardenItem extends BitwardenItemMetadata {

    @CheckForNull
    public final Secret notes;

    @CheckForNull
    public final BitwardenLogin login;

    @CheckForNull
    public final BitwardenSshKey sshKey;

    @JsonCreator
    public BitwardenItem(
            @NonNull @JsonProperty("id") String id,
            @NonNull @JsonProperty("name") String name,
            @NonNull @JsonProperty("type") BitwardenItemType type,
            @CheckForNull @JsonProperty("notes") @JsonDeserialize(using = SecretDeserializer.class) Secret notes,
            @CheckForNull @JsonProperty("login") BitwardenLogin login,
            @CheckForNull @JsonProperty("sshKey") BitwardenSshKey sshKey) {
        super(id, name, type);
        this.notes = notes;
        this.login = login;
        this.sshKey = sshKey;
    }
}
