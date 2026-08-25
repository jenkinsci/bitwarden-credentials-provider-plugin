package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;

/**
 * Converts Bitwarden items into Jenkins {@link StandardCredentials}.
 */
public sealed interface CredentialConverter
        permits LoginConverter, SecureNoteStringConverter, SecureNoteFileConverter, SshKeyConverter {

    /**
     * Returns a converter that supports the given item, if one exists.
     *
     * @param metadata the Bitwarden item metadata
     * @return a suitable converter instance, or {@code null} if none is found
     */
    @CheckForNull
    static CredentialConverter getConverter(@NonNull BitwardenItemMetadata metadata) {
        Class<? extends CredentialConverter> converterClass =
                switch (metadata.type) {
                    case LOGIN -> LoginConverter.class;
                    case SECURE_NOTE ->
                        BitwardenConfig.getInstance().hasFileCredentialSuffix(metadata.name)
                                ? SecureNoteFileConverter.class
                                : SecureNoteStringConverter.class;
                    case SSH_KEY -> SshKeyConverter.class;
                    case CARD, IDENTITY, UNKNOWN -> null;
                };
        return converterClass != null ? ExtensionList.lookupSingleton(converterClass) : null;
    }

    /**
     * Creates a lightweight, lazy-loading {@link CredentialProxy}.
     *
     * @param id the Jenkins credential ID
     * @param metadata the Bitwarden item metadata
     * @return a Jenkins credential proxy
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
     * @return the concrete Jenkins credential
     */
    @NonNull
    StandardCredentials convert(@NonNull String id, @NonNull String description, @NonNull BitwardenItem item);
}
