package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import hudson.Extension;
import hudson.model.Descriptor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

/**
 * Converts Bitwarden Secure Note items into a Jenkins {@link StringCredentials}
 * or {@link FileCredentials} proxy. The specific type is determined by the item's name.
 */
@Extension
public class SecureNoteConverter extends CredentialConverter {

    private static final Logger LOGGER = Logger.getLogger(SecureNoteConverter.class.getName());

    /**
     * {@inheritDoc}
     * <p>
     * This implementation returns {@code true} if the item's type is {@link BitwardenItemType#SECURE_NOTE}.
     */
    @Override
    public boolean canConvert(BitwardenItemMetadata metadata) {
        return metadata.getItemType() == BitwardenItemType.SECURE_NOTE;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation returns {@code true} if the Bitwarden item contains a non-null {@code notes} field.
     */
    @Override
    public boolean canConvert(BitwardenItem item) {
        boolean canConvert = item.getNotes() != null;
        LOGGER.fine(() ->
                "canConvert: item id=" + item.getId() + " name='" + item.getName() + "' canConvert=" + canConvert);
        return canConvert;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation creates a proxy for either {@link StringCredentials} or
     * {@link FileCredentials}, based on whether the item name ends with {@code .env}.
     */
    @Override
    public StandardCredentials createProxy(CredentialsScope scope, String id, BitwardenItemMetadata metadata) {
        LOGGER.fine(() -> "Creating PROXY credential for secure note: " + metadata.getId());
        if (metadata.getName().trim().toLowerCase().endsWith(".env")) {
            LOGGER.fine(() -> "Proxying as FileCredentials due to .env suffix");
            Descriptor<?> descriptor = Jenkins.get().getDescriptor(FileCredentialsImpl.class);
            if (descriptor == null) {
                LOGGER.warning(
                        "Descriptor for FileCredentialsImpl not found. Is the Plain Credentials plugin installed and enabled?");
                return null;
            }
            CredentialProxy handler = new CredentialProxy(id, metadata.getId(), metadata.getName(), descriptor);
            return (FileCredentials) Proxy.newProxyInstance(
                    FileCredentials.class.getClassLoader(), new Class<?>[] {FileCredentials.class}, handler);
        } else {
            LOGGER.fine(() -> "Proxying as StringCredentials");
            Descriptor<?> descriptor = Jenkins.get().getDescriptor(StringCredentialsImpl.class);
            if (descriptor == null) {
                LOGGER.warning(
                        "Descriptor for StringCredentialsImpl not found. Is the Plain Credentials plugin installed and enabled?");
                return null;
            }
            CredentialProxy handler = new CredentialProxy(id, metadata.getId(), metadata.getName(), descriptor);
            return (StringCredentials) Proxy.newProxyInstance(
                    StringCredentials.class.getClassLoader(), new Class<?>[] {StringCredentials.class}, handler);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation returns a {@link StringCredentialsImpl} using the content of the
     * {@code notes} field, or a {@link FileCredentialsImpl} if the item name ends with {@code .env}.
     */
    @Override
    public StandardCredentials convert(CredentialsScope scope, String id, String description, BitwardenItem item) {
        LOGGER.fine(() -> "convert: id=" + id + " item id=" + item.getId() + " name='" + item.getName() + "'");
        if (item.getName().trim().toLowerCase().endsWith(".env")) {
            LOGGER.fine(() -> "convert: treating as FileCredentialsImpl due to .env suffix");
            return new FileCredentialsImpl(
                    scope,
                    id,
                    description,
                    item.getName(),
                    SecretBytes.fromRawBytes(item.getNotes().getPlainText().getBytes(StandardCharsets.UTF_8)));
        } else {
            LOGGER.fine(() -> "convert: treating as StringCredentialsImpl");
            return new StringCredentialsImpl(scope, id, description, item.getNotes());
        }
    }
}
