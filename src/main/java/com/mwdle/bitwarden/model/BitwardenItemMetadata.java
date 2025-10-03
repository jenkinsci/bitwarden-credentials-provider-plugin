package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a lightweight, non-secret metadata object for a Bitwarden item.
 * <p>
 * This class is used for caching the list of all items from the vault without storing
 * any sensitive information in memory or on disk. It is deserialized from the JSON
 * output of {@code bw list items}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitwardenItemMetadata implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private BitwardenItemType itemType;

    /**
     * Gets the unique UUID of the item.
     *
     * @return The unique UUID of the item.
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the user-provided name of the item.
     *
     * @return The user-provided name of the item.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the enumerated type of the Bitwarden item.
     *
     * @return The {@link BitwardenItemType} of the item.
     */
    public BitwardenItemType getItemType() {
        return itemType;
    }
}
