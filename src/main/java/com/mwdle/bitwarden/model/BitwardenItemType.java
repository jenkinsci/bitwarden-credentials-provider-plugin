package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.stream.Stream;

/**
 * Represents the type of Bitwarden item, mapped from the integer code provided by the CLI.
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
     * TODO
     * @param typeCode #TODO
     */
    BitwardenItemType(int typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * This annotation tells Jackson to use this method to create an
     * enum instance from the integer value in the JSON.
     *
     * @param typeCode The integer value from the "type" field in the JSON.
     * @return The corresponding BitwardenItemType enum constant.
     */
    @JsonCreator
    public static BitwardenItemType fromInteger(int typeCode) {
        return Stream.of(BitwardenItemType.values())
                .filter(type -> type.typeCode == typeCode)
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * This annotation tells Jackson that when serializing this enum, it should
     * write out the integer value. (Less critical for us, but good practice).
     *
     * @return The integer code for the enum constant.
     */
    @JsonValue
    public int getTypeCode() {
        return typeCode;
    }
}
