package com.mwdle.bitwarden.converters;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.DirectEntryPrivateKeySource;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenSshKey;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.Objects;

/**
 * Converts {@link BitwardenItemType#SSH_KEY} items into Jenkins {@link BasicSSHUserPrivateKey}.
 */
@Extension
public final class SshKeyConverter implements CredentialConverter {

    /**
     * Derives a username from the public key's comment, if available.
     * <p>
     * If the comment is in "user@host" or email format, it extracts the "user" part.
     * Otherwise, it falls back to using an empty string.
     *
     * @param publicKey the SSH public key from the Bitwarden item
     * @return the derived username, or an empty string if it cannot be determined
     */
    @NonNull
    private static String getUsername(@CheckForNull String publicKey) {
        String username = "";
        if (publicKey != null && !publicKey.isBlank()) {
            String[] parts = publicKey.strip().split("\\s+");
            if (parts.length > 2) {
                String comment = parts[2];
                if (comment.contains("@")) {
                    username = comment.split("@", 2)[0].strip();
                }
            }
        }
        return username;
    }

    @Override
    @NonNull
    public BitwardenItemType supportedType() {
        return BitwardenItemType.SSH_KEY;
    }

    /**
     * {@inheritDoc}
     *
     * @return a proxy object that implements {@link SSHUserPrivateKey}
     */
    @Override
    @NonNull
    public SSHUserPrivateKey createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(getClass(), id, metadata, SSHUserPrivateKey.class, BasicSSHUserPrivateKey.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return a concrete {@link BasicSSHUserPrivateKey}, deriving the username from the public key's comment field if available
     */
    @Override
    @NonNull
    public BasicSSHUserPrivateKey convert(
            @NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        BitwardenSshKey sshKeyData =
                Objects.requireNonNull(item.sshKey, "Bitwarden item is type SSH_KEY but missing SSH Key data!");
        String username = getUsername(sshKeyData.publicKey());
        DirectEntryPrivateKeySource privateKeySource = new DirectEntryPrivateKeySource(sshKeyData.privateKey());
        // Pass in null for the passphrase since Bitwarden does not provide such a field
        return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, id, username, privateKeySource, null, description);
    }
}
