package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/**
 * Converts {@link BitwardenItemType#SECURE_NOTE} items into Jenkins {@link StringCredentialsImpl}.
 */
@Extension
public final class SecureNoteStringConverter implements CredentialConverter {

    @Override
    @NonNull
    public StringCredentials createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(id, metadata, StringCredentials.class, StringCredentialsImpl.class, getClass());
    }

    @Override
    @NonNull
    public StringCredentialsImpl convert(@NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        Secret notes = item.notes != null ? item.notes : Secret.fromString("");
        return new StringCredentialsImpl(CredentialsScope.GLOBAL, id, description, notes);
    }
}
