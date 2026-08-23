package com.mwdle.bitwarden.converters;

import static com.mwdle.bitwarden.util.StringUtils.stripToNull;

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
import java.util.Optional;

/**
 * Converts {@link BitwardenItemType#SSH_KEY} items into Jenkins {@link BasicSSHUserPrivateKey}.
 */
@Extension
public final class SshKeyConverter implements CredentialConverter {

    @Override
    @NonNull
    public SSHUserPrivateKey createProxy(@NonNull String id, @NonNull BitwardenItemMetadata metadata) {
        return CredentialProxy.create(id, metadata, SSHUserPrivateKey.class, BasicSSHUserPrivateKey.class, getClass());
    }

    @Override
    @NonNull
    public BasicSSHUserPrivateKey convert(
            @NonNull String id, @NonNull String description, @NonNull BitwardenItem item) {
        BitwardenSshKey sshKeyData =
                Objects.requireNonNull(item.sshKey, "Bitwarden item is an SSH key but missing SSH key data!");
        String username = getUsername(sshKeyData.publicKey());
        DirectEntryPrivateKeySource privateKeySource =
                sshKeyData.privateKey() != null ? new DirectEntryPrivateKeySource(sshKeyData.privateKey()) : null;
        return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, id, username, privateKeySource, null, description);
    }

    /**
     * Derives a username from the public key's comment, if available.
     * <p>
     * If the comment is in "user@host" or email format, extracts the "user" part.
     * Otherwise, falls back to an empty string.
     *
     * @param publicKey the SSH public key from the Bitwarden item
     * @return the derived username, or an empty string if it cannot be determined
     */
    @NonNull
    private static String getUsername(@CheckForNull String publicKey) {
        return Optional.ofNullable(stripToNull(publicKey))
                .map(key -> key.split("\\s+", 3))
                .filter(parts -> parts.length > 2 && parts[2].contains("@"))
                .map(parts -> parts[2].substring(0, parts[2].indexOf('@')).strip())
                .orElse("");
    }
}
