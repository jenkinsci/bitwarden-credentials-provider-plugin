package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

/**
 * Represents a fully resolved Bitwarden item object, deserialized from the JSON output of {@code bw get item}.
 * <p>
 * This class models all fields, including secrets, relevant to the plugin for converting a
 * Bitwarden item into a concrete Jenkins credential.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
public class BitwardenItem {
    /**
     * The unique UUID of the item.
     */
    private String id;
    /**
     * The user-provided name of the item.
     */
    private String name;
    /**
     * The content of the item's "notes" field.
     */
    @JsonDeserialize(using = SecretDeserializer.class)
    private Secret notes;
    /**
     * The nested object containing login details, if this item is a Login.
     */
    private BitwardenLogin login;
    /**
     * The nested object containing SSH key details, if this item is an SSH Key.
     */
    private BitwardenSshKey sshKey;

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
     * Gets the content of the item's "notes" field.
     *
     * @return The content of the item's "notes" field as a {@link Secret}.
     */
    public Secret getNotes() {
        return notes;
    }

    /**
     * Gets the nested object containing login details.
     *
     * @return The {@link BitwardenLogin} object, or {@code null} if this is not a Login item.
     */
    public BitwardenLogin getLogin() {
        return login;
    }

    /**
     * Gets the nested object containing SSH key details.
     *
     * @return The {@link BitwardenSshKey} object, or {@code null} if this is not an SSH Key item.
     */
    public BitwardenSshKey getSshKey() {
        return sshKey;
    }
}
