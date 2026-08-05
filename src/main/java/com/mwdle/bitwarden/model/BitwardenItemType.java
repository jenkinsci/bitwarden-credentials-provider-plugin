package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Represents the type of Bitwarden item.
 */
public enum BitwardenItemType {
    UNKNOWN,
    LOGIN,
    SECURE_NOTE,
    CARD,
    IDENTITY,
    SSH_KEY;

    /**
     * Maps a Bitwarden CLI integer type code to its corresponding enum constant.
     *
     * @param typeCode the integer code from the Bitwarden CLI output representing the item type
     * @return the corresponding {@link BitwardenItemType}, or {@link #UNKNOWN} if the code is unrecognized
     */
    @JsonCreator
    public static BitwardenItemType fromInteger(int typeCode) {
        return switch (typeCode) {
            case 1 -> LOGIN;
            case 2 -> SECURE_NOTE;
            case 3 -> CARD;
            case 4 -> IDENTITY;
            case 5 -> SSH_KEY;
            default -> UNKNOWN;
        };
    }
}
