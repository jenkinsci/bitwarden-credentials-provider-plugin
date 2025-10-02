package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

/**
 * Represents a top-level Bitwarden item object, deserialized from the JSON output of the {@code bw} CLI.
 * <p>
 * This class models the fields relevant to the plugin for converting Bitwarden items
 * into Jenkins credentials.
 */
// This Jackson annotation ensures that if the Bitwarden CLI adds new, unknown fields
// to its JSON output in the future, the deserialization process will not fail.
@JsonIgnoreProperties(ignoreUnknown = true)
// Suppress SpotBugs warning for fields populated by the Jackson JSON parser
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
     * @return The unique UUID of the item.
     */
    public String getId() {
        return id;
    }

    /**
     * @return The user-provided name of the item.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The content of the item's "notes" field.
     */
    public Secret getNotes() {
        return notes;
    }

    /**
     * @return The nested object containing login details, or null if not a Login item.
     */
    public BitwardenLogin getLogin() {
        return login;
    }

    /**
     * @return The nested object containing SSH key details, or null if not an SSH Key item.
     */
    public BitwardenSshKey getSshKey() {
        return sshKey;
    }
}
