package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenSshKey;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that {@link SshKeyConverter} builds SSH credentials and derives the username from the public key comment.
 */
@WithJenkins
@DisplayName("SshKeyConverter.convert")
class SshKeyConverterTest {

    private final SshKeyConverter converter = new SshKeyConverter();

    private static BitwardenItem sshItem(@CheckForNull String privateKey, @CheckForNull String publicKey) {
        BitwardenSshKey sshKey = new BitwardenSshKey(Secret.fromString(privateKey), publicKey);
        return new BitwardenItem("id", "sshkey", BitwardenItemType.SSH_KEY, null, null, sshKey);
    }

    @Test
    @DisplayName("preserves the private key content")
    void preservesPrivateKey(JenkinsRule ignored) {
        BasicSSHUserPrivateKey credential =
                converter.convert("cred-id", "desc", sshItem("PRIVATE_KEY_BODY", "ssh-ed25519 AAAA user@host"));

        assertEquals("cred-id", credential.getId());
        // The SSH credentials plugin normalizes private keys to end with a trailing newline.
        assertEquals("PRIVATE_KEY_BODY", credential.getPrivateKeys().get(0).strip());
    }

    @ParameterizedTest
    @CsvSource({
        "'ssh-ed25519 AAAA jenkins@my-server', jenkins",
        "'ssh-rsa AAAB deploy@ci', deploy",
    })
    @DisplayName("derives the username from the public key comment")
    void derivesUsernameFromComment(String publicKey, String expectedUsername, JenkinsRule ignored) {
        BasicSSHUserPrivateKey credential = converter.convert("cred-id", "desc", sshItem("KEY", publicKey));

        assertEquals(expectedUsername, credential.getUsername());
    }

    @ParameterizedTest
    @CsvSource({
        "'ssh-ed25519 AAAA'", // no comment
        "'ssh-ed25519 AAAA no-at-sign-comment'", // comment without '@'
    })
    @DisplayName("falls back to the controller OS user when the comment is not parseable")
    void fallsBackToSystemUserWhenNoComment(String publicKey, JenkinsRule ignored) {
        BasicSSHUserPrivateKey credential = converter.convert("cred-id", "desc", sshItem("KEY", publicKey));

        // The converter derives an empty username, and BasicSSHUserPrivateKey then falls back to the OS user
        // (as documented in the plugin README).
        assertEquals(System.getProperty("user.name"), credential.getUsername());
    }

    @Test
    @DisplayName("falls back to the controller OS user when the public key is missing")
    void fallsBackToSystemUserWhenPublicKeyNull(JenkinsRule ignored) {
        BasicSSHUserPrivateKey credential = converter.convert("cred-id", "desc", sshItem("KEY", null));

        assertEquals(System.getProperty("user.name"), credential.getUsername());
    }

    @Test
    @DisplayName("handles a missing private key gracefully")
    void handlesNullPrivateKey(JenkinsRule ignored) {
        BitwardenSshKey sshKey = new BitwardenSshKey(null, "ssh-ed25519 AAAA user@host");
        BitwardenItem item = new BitwardenItem("id", "sshkey", BitwardenItemType.SSH_KEY, null, null, sshKey);

        BasicSSHUserPrivateKey credential = converter.convert("cred-id", "desc", item);

        assertEquals("cred-id", credential.getId());
        assertEquals(0, credential.getPrivateKeys().size());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("handles null, empty, or blank public keys safely")
    void handlesBlankPublicKey(String publicKey, JenkinsRule ignored) {
        BasicSSHUserPrivateKey credential = converter.convert("cred-id", "desc", sshItem("KEY", publicKey));

        assertEquals(System.getProperty("user.name"), credential.getUsername());
    }
}
