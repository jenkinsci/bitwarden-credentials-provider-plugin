package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenLogin;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.Secret;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Converts Bitwarden Login items into a Jenkins {@link StandardUsernamePasswordCredentials}.
 */
@Extension
public class LoginConverter extends CredentialConverter {

    private static final Logger LOGGER = Logger.getLogger(LoginConverter.class.getName());

    /**
     * {@inheritDoc}
     * <p>
     * Returns true if the item's type is {@link BitwardenItemType#LOGIN}.
     */
    @Override
    public boolean canConvert(BitwardenItemMetadata metadata) {
        return metadata.getItemType() == BitwardenItemType.LOGIN;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns true if the Bitwarden item contains a non-null {@code login} object.
     */
    @Override
    public boolean canConvert(BitwardenItem item) {
        boolean canConvert = item.getLogin() != null
                && (item.getLogin().getUsername() != null || item.getLogin().getPassword() != null);
        LOGGER.fine(() ->
                "canConvert: item id=" + item.getId() + " name='" + item.getName() + "' canConvert=" + canConvert);
        return canConvert;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Creates a dynamic proxy that implements {@link StandardUsernamePasswordCredentials}.
     * The proxy will fetch the username and password from Bitwarden on-demand when
     * {@code getUsername()} or {@code getPassword()} are called.
     */
    @Override
    public StandardUsernamePasswordCredentials createProxy(
            CredentialsScope scope, String id, BitwardenItemMetadata metadata) {
        LOGGER.fine(() -> "Creating PROXY credential for login item: " + metadata.getId());

        Descriptor<?> descriptor = Jenkins.get().getDescriptor(UsernamePasswordCredentialsImpl.class);
        if (descriptor == null) {
            LOGGER.warning(
                    "Descriptor for UsernamePasswordCredentialsImpl not found. Is the Credentials plugin installed and enabled?");
            return null;
        }

        CredentialProxy handler = new CredentialProxy(id, metadata.getId(), metadata.getName(), descriptor);
        return (StandardUsernamePasswordCredentials) Proxy.newProxyInstance(
                StandardUsernamePasswordCredentials.class.getClassLoader(),
                new Class<?>[] {StandardUsernamePasswordCredentials.class},
                handler);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Constructs a {@link UsernamePasswordCredentialsImpl} using the username and password
     * from the Bitwarden item. Safely handles nulls by returning empty strings.
     * This means that either the username or password field must always be present if the fetch succeeds.
     */
    @Override
    public StandardUsernamePasswordCredentials convert(
            CredentialsScope scope, String id, String description, BitwardenItem item) {
        LOGGER.fine(() -> "convert: id=" + id + " item id=" + item.getId() + " name='" + item.getName() + "'");
        BitwardenLogin loginData = item.getLogin();
        try {
            Secret username = (loginData.getUsername() != null) ? loginData.getUsername() : Secret.fromString("");
            Secret password = (loginData.getPassword() != null) ? loginData.getPassword() : Secret.fromString("");
            return new UsernamePasswordCredentialsImpl(
                    scope, id, description, username.getPlainText(), password.getPlainText());
        } catch (Descriptor.FormException e) {
            LOGGER.warning(() ->
                    "Failed to create credentials for id=" + id + " name='" + item.getName() + "': " + e.getMessage());
            return null;
        }
    }
}
