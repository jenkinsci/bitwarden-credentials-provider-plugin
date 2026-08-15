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
import java.util.Optional;

/**
 * Converts Bitwarden {@link BitwardenItemType#LOGIN} items into Jenkins {@link UsernamePasswordCredentialsImpl}.
 */
@Extension
public final class LoginConverter implements CredentialConverter {

    @Override
    @NonNull
    public BitwardenItemType supportedType() {
        return BitwardenItemType.LOGIN;
    }

    /**
     * {@inheritDoc}
     *
     * @return a Jenkins credential proxy implementing {@link StandardUsernamePasswordCredentials}
     */
    @Override
    @NonNull
    public StandardUsernamePasswordCredentials createProxy(
            @NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(
                getClass(),
                id,
                metadata,
                StandardUsernamePasswordCredentials.class,
                UsernamePasswordCredentialsImpl.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return a concrete {@link UsernamePasswordCredentialsImpl} using the username and
     * password from the Bitwarden item, safely handling null values by substituting empty strings
     */
    @Override
    @NonNull
    public UsernamePasswordCredentialsImpl convert(
            @NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        BitwardenLogin loginData =
                Objects.requireNonNull(item.login, "Bitwarden item is type LOGIN but missing login data!");
        Secret username = Optional.ofNullable(loginData.username()).orElseGet(() -> Secret.fromString(""));
        Secret password = Optional.ofNullable(loginData.password()).orElseGet(() -> Secret.fromString(""));
        try {
            return new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL, id, description, username.getPlainText(), password.getPlainText());
        } catch (Descriptor.FormException e) {
            throw new IllegalStateException(
                    "Failed to create Username/Password credential for Bitwarden Item '%s' (ID: %s)"
                            .formatted(item.name, item.id),
                    e);
        }
    }
}
