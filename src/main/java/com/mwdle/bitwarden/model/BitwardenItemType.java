package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.stream.Stream;

/**
 * Represents the type of Bitwarden item, mapped from the integer code provided by the Bitwarden CLI.
 * This enum is designed to be directly used in Jackson deserialization.
 */
public enum BitwardenItemType {
    LOGIN(1),
    SECURE_NOTE(2),
    CARD(3),
    IDENTITY(4),
    SSH_KEY(5),

    /**
     * A fallback for any unknown or unsupported item types.
     */
    UNKNOWN(0);

    private final int typeCode;

    /**
     * Constructs an enum constant with its corresponding integer code from the Bitwarden CLI.
     *
     * @param typeCode the integer code representing the item type
     */
    BitwardenItemType(int typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Deserializes an integer from JSON into the corresponding {@link BitwardenItemType}.
     * This method is used by the Jackson library.
     *
     * @param typeCode the integer value from the "type" field in the JSON
     * @return the corresponding {@link BitwardenItemType}, or {@link #UNKNOWN} if not found
     */
    @JsonCreator
    public static BitwardenItemType fromInteger(int typeCode) {
        return Stream.of(BitwardenItemType.values())
                .filter(type -> type.typeCode == typeCode)
                .findFirst()
                .orElse(UNKNOWN);
    }

}
