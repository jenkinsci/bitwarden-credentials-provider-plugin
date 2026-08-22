package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;

/**
 * Converts {@link BitwardenItemType#SECURE_NOTE} items into Jenkins {@link FileCredentialsImpl}.
 */
@Extension
public final class SecureNoteFileConverter implements CredentialConverter {

    @Override
    @NonNull
    public StandardCredentials createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(getClass(), id, metadata, FileCredentials.class, FileCredentialsImpl.class);
    }

    @Override
    @NonNull
    public FileCredentialsImpl convert(@NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        SecretBytes notes = SecretBytes.fromRawBytes(
                item.notes != null ? item.notes.getPlainText().getBytes(StandardCharsets.UTF_8) : new byte[0]);
        return new FileCredentialsImpl(CredentialsScope.GLOBAL, id, description, item.name, notes);
    }
}
