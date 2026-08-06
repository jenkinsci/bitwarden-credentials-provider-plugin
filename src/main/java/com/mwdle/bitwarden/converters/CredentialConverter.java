package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * Converts Bitwarden items into Jenkins {@link StandardCredentials}.
 */
public interface CredentialConverter extends ExtensionPoint {

    /**
     * Finds the first registered converter that supports the given item type.
     *
     * @param type the Bitwarden item type
     * @return a suitable converter instance, or {@code null} if none is found
     */
    @CheckForNull
    static CredentialConverter getConverter(@NonNull BitwardenItemType type) {
        for (CredentialConverter converter : ExtensionList.lookup(CredentialConverter.class)) {
            if (converter.supportedType() == type) {
                return converter;
            }
        }
        return null;
    }

    /**
     * @return the {@link BitwardenItemType} this converter handles
     */
    @NonNull
    BitwardenItemType supportedType();

    /**
     * Creates a lightweight, lazy-loading {@link CredentialProxy}.
     *
     * @param id the Jenkins credential ID
     * @param metadata the item metadata
     * @return a standard credentials proxy
     */
    @NonNull
    StandardCredentials createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata);

    /**
     * Creates a concrete Jenkins {@link StandardCredentials}.
     * <p>
     * Called by the {@link CredentialProxy} after the full item is fetched from the Bitwarden CLI.
     *
     * @param id the Jenkins credential ID
     * @param description the user-facing description
     * @param item the Bitwarden item
     * @return the concrete Jenkins credential instance
     */
    @NonNull
    StandardCredentials convert(@NonNull String id, @NonNull String description, @NonNull BitwardenItem item);
}
