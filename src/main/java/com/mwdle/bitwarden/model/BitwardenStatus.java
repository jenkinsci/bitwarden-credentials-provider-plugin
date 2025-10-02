package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Represents the JSON response from the {@code bw status} command.
 * <p>
 * This class models the fields relevant to the plugin for checking the current
 * status of the Bitwarden CLI session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// Suppress SpotBugs warning for fields populated by the Jackson JSON parser
@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
public class BitwardenStatus {
    /**
     * The current status of the vault, e.g., "unlocked", "locked", or "unauthenticated".
     */
    private String status;

    /**
     * Gets the current status of the Bitwarden CLI session.
     *
     * @return The status string (e.g., "unlocked", "locked").
     */
    public String getStatus() {
        return status;
    }
}
