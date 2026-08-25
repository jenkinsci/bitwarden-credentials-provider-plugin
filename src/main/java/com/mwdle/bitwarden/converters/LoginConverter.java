package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenLogin;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.Secret;
import java.util.Objects;

/**
 * Converts {@link BitwardenItemType#LOGIN} items into Jenkins {@link UsernamePasswordCredentialsImpl}.
 */
@Extension
public final class LoginConverter implements CredentialConverter {

    @Override
    @NonNull
    public StandardUsernamePasswordCredentials createProxy(
            @NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(
                id,
                metadata,
                StandardUsernamePasswordCredentials.class,
                UsernamePasswordCredentialsImpl.class,
                getClass());
    }

    @Override
    @NonNull
    public UsernamePasswordCredentialsImpl convert(
            @NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        BitwardenLogin login = Objects.requireNonNull(item.login, "Bitwarden item is a login but missing login data!");
        Secret user = login.username();
        Secret pass = login.password();
        Secret username = user != null ? user : Secret.fromString("");
        Secret password = pass != null ? pass : Secret.fromString("");
        try {
            return new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL, id, description, username.getPlainText(), password.getPlainText());
        } catch (Descriptor.FormException e) {
            throw new IllegalStateException(
                    "Username/Password credential creation failed for Bitwarden Item '%s' (ID: %s)"
                            .formatted(item.name, item.id),
                    e);
        }
    }
}
