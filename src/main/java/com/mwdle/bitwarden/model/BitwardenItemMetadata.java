package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The lightweight, non-secret metadata of a Bitwarden item, deserialized from {@code bw list items}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitwardenItemMetadata {

    @NonNull
    public final String id;

    @NonNull
    public final String name;

    @NonNull
    public final BitwardenItemType type;

    @JsonCreator
    public BitwardenItemMetadata(
            @NonNull @JsonProperty("id") String id,
            @NonNull @JsonProperty("name") String name,
            @NonNull @JsonProperty("type") BitwardenItemType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }
}
