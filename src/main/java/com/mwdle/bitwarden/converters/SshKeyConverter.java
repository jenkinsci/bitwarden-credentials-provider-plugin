package com.mwdle.bitwarden.converters;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenSshKey;
import hudson.Extension;
import hudson.model.Descriptor;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Converts Bitwarden SSH Key items into a Jenkins {@link com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey} proxy.
 */
@Extension
public class SshKeyConverter extends CredentialConverter {

    private static final Logger LOGGER = Logger.getLogger(SshKeyConverter.class.getName());

    /**
     * Derives the username from the public key comment if available.
     * <p>
     * If the public key comment contains a string in the form "user@host", the username will be the part before the '@'.
     *
     * @param sshKeyData the SSH key data from the Bitwarden item.
     * @return the derived username, or an empty string if not derivable.
     */
    private static String getUsername(BitwardenSshKey sshKeyData) {
        String username = "";
        String publicKey = sshKeyData.getPublicKey();
        if (publicKey != null) {
            String[] parts = publicKey.trim().split("\\s+");
            if (parts.length > 2) {
                // Get the last part, which is the comment (e.g., "user@host")
                String comment = parts[parts.length - 1];
                username = comment.split("@")[0];
            }
        }
        return username;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns true if the item's type is {@link BitwardenItemType#SSH_KEY}.
     */
    @Override
    public boolean canConvert(BitwardenItemMetadata metadata) {
        return metadata.getItemType() == BitwardenItemType.SSH_KEY;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation returns true if the Bitwarden item contains a non-null {@code privateKey} field.
     */
    @Override
    public boolean canConvert(BitwardenItem item) {
        BitwardenSshKey sshKeyData = item.getSshKey();
        boolean canConvert = sshKeyData != null && sshKeyData.getPrivateKey() != null;
        LOGGER.fine(() ->
                "canConvert: item id=" + item.getId() + " name='" + item.getName() + "' canConvert=" + canConvert);
        return canConvert;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Creates a dynamic proxy that implements {@code SSHUserPrivateKey}.
     * The proxy will fetch the private key and derive the username from the public key comment
     * on-demand.
     */
    @Override
    public SSHUserPrivateKey createProxy(CredentialsScope scope, String id, BitwardenItemMetadata metadata) {
        LOGGER.fine(() -> "Creating PROXY credential for SSH key: " + metadata.getId());

        Descriptor<?> descriptor = Jenkins.get().getDescriptor(BasicSSHUserPrivateKey.class);
        if (descriptor == null) {
            LOGGER.warning(
                    "Descriptor for BasicSSHUserPrivateKey not found. Is the Credentials plugin installed and enabled?");
            return null;
        }

        CredentialProxy handler = new CredentialProxy(id, metadata.getId(), metadata.getName(), descriptor);
        return (SSHUserPrivateKey) Proxy.newProxyInstance(
                SSHUserPrivateKey.class.getClassLoader(), new Class<?>[] {SSHUserPrivateKey.class}, handler);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation returns a {@link BasicSSHUserPrivateKey}, deriving the username from the public key comment if available.
     */
    @Override
    public BasicSSHUserPrivateKey convert(CredentialsScope scope, String id, String description, BitwardenItem item) {
        LOGGER.fine(() -> "convert: id=" + id + " item id=" + item.getId() + " name='" + item.getName() + "'");
        BitwardenSshKey sshKeyData = item.getSshKey();
        String username = getUsername(sshKeyData);
        LOGGER.fine(() -> "convert: derived username='" + username + "'");
        BasicSSHUserPrivateKey.DirectEntryPrivateKeySource privateKeySource =
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(sshKeyData.getPrivateKey());

        return new BasicSSHUserPrivateKey(scope, id, username, privateKeySource, "", description);
    }
}
