package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/**
 * Converts {@link BitwardenItemType#SECURE_NOTE} items into Jenkins {@link StringCredentialsImpl}
 * or {@link FileCredentialsImpl}.
 */
@Extension
public final class SecureNoteConverter implements CredentialConverter {

    /**
     * Checks if a given item name ends with one of the user-configured suffixes for file credentials.
     *
     * @param name the name of the Bitwarden item
     * @return {@code true} if the name matches a configured suffix
     */
    private static boolean hasFileCredentialSuffix(@NonNull String name) {
        String suffixes = BitwardenConfig.getInstance().getFileCredentialSuffixes();
        if (suffixes == null) {
            return false;
        }
        // Split by comma and strip whitespace from each entry
        String strippedName = name.strip();
        return Arrays.stream(suffixes.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .anyMatch(strippedName::endsWith);
    }

    @Override
    @NonNull
    public BitwardenItemType supportedType() {
        return BitwardenItemType.SECURE_NOTE;
    }

    /**
     * {@inheritDoc}
     *
     * @return a Jenkins credential proxy implementing {@link StringCredentials} or {@link FileCredentials}, depending on the result of {@link SecureNoteConverter#hasFileCredentialSuffix}
     */
    @Override
    @NonNull
    public StandardCredentials createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        if (hasFileCredentialSuffix(metadata.name)) {
            return CredentialProxy.create(getClass(), id, metadata, FileCredentials.class, FileCredentialsImpl.class);
        } else {
            return CredentialProxy.create(
                    getClass(), id, metadata, StringCredentials.class, StringCredentialsImpl.class);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return a concrete {@link StringCredentialsImpl} or {@link FileCredentialsImpl} using the content
     * of the {@code notes} field, depending on the result of {@link SecureNoteConverter#hasFileCredentialSuffix}
     */
    @Override
    @NonNull
    public BaseStandardCredentials convert(
            @NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        Secret notes = Optional.ofNullable(item.notes).orElseGet(() -> Secret.fromString(""));
        if (hasFileCredentialSuffix(item.name)) {
            return new FileCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    id,
                    description,
                    item.name,
                    SecretBytes.fromRawBytes(notes.getPlainText().getBytes(StandardCharsets.UTF_8)));
        } else {
            return new StringCredentialsImpl(CredentialsScope.GLOBAL, id, description, notes);
        }
    }
}
