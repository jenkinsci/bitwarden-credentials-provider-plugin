package com.mwdle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;

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

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * @return The enumerated type of the Bitwarden item.
     */
    public BitwardenItemType getItemType() {
        return itemType;
    }
}
