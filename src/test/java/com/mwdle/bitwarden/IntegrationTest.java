package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@DisplayName("Integration Test")
@EnabledIfEnvironmentVariable(named = "BWCP_API_CLIENT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "BWCP_API_CLIENT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "BWCP_MASTER_PASSWORD", matches = ".+")
class IntegrationTest {

    private static final String API_CREDENTIAL_ID = "bitwarden-api-key";
    private static final String MASTER_PASSWORD_CREDENTIAL_ID = "bitwarden-master-password";

    @BeforeEach
    void setUp(JenkinsRule ignored) throws Exception {
        SystemCredentialsProvider store = SystemCredentialsProvider.getInstance();
        store.getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        API_CREDENTIAL_ID,
                        "Bitwarden API key",
                        System.getenv("BWCP_API_CLIENT_ID"),
                        System.getenv("BWCP_API_CLIENT_SECRET")));
        store.getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        MASTER_PASSWORD_CREDENTIAL_ID,
                        "Bitwarden master password",
                        Secret.fromString(System.getenv("BWCP_MASTER_PASSWORD"))));
        BitwardenConfig config = BitwardenConfig.getInstance();
        config.setServerUrl(System.getenv("BWCP_SERVER_URL"));
        config.setApiCredentialId(API_CREDENTIAL_ID);
        config.setMasterPasswordCredentialId(MASTER_PASSWORD_CREDENTIAL_ID);
        config.setFileCredentialSuffixes(".env,.yaml");
        config.save();
    }

    @TestFactory
    @DisplayName("resolves vault credentials")
    Stream<DynamicTest> runAllTests() {
        return Stream.of(
                dynamicTest("resolves login", this::resolvesLogin),
                dynamicTest("resolves login", this::resolvesEmptyLogin),
                dynamicTest("resolves login with empty username", this::resolvesLoginEmptyUsername),
                dynamicTest("resolves login with empty password", this::resolvesLoginEmptyPassword),
                dynamicTest("resolves note", this::resolvesStringNote),
                dynamicTest("resolves empty note", this::resolvesEmptyNote),
                dynamicTest("resolves file note", this::resolvesFileNote),
                dynamicTest("resolves SSH key", this::resolvesSshKey),
                dynamicTest("resolves SSH key with empty comment", this::resolvesSshKeyEmptyComment),
                dynamicTest("resolves duplicates", this::resolvesDuplicates),
                dynamicTest("resolves bad names", this::resolvesBadNames),
                dynamicTest("ignores card items", this::ignoresCardItems),
                dynamicTest("ignores identity items", this::ignoresIdentityItems),
                dynamicTest("ignores non-existent items", this::ignoresNonExistentItems));
    }

    private void resolvesLogin() {
        StandardUsernamePasswordCredentials login =
                lookupCredentialById("login", StandardUsernamePasswordCredentials.class);
        assertEquals("username", login.getUsername());
        assertEquals("password", login.getPassword().getPlainText());
    }

    private void resolvesEmptyLogin() {
        StandardUsernamePasswordCredentials login =
                lookupCredentialById("empty login", StandardUsernamePasswordCredentials.class);
        assertEquals("", login.getUsername());
        assertEquals("", login.getPassword().getPlainText());
    }

    private void resolvesLoginEmptyUsername() {
        StandardUsernamePasswordCredentials login =
                lookupCredentialById("login empty username", StandardUsernamePasswordCredentials.class);
        assertTrue(login.getUsername().isEmpty(), "Username should be empty or null");
        assertNotNull(login.getPassword().getPlainText());
    }

    private void resolvesLoginEmptyPassword() {
        StandardUsernamePasswordCredentials login =
                lookupCredentialById("login empty password", StandardUsernamePasswordCredentials.class);
        assertNotNull(login.getUsername());
        assertTrue(login.getPassword().getPlainText().isEmpty(), "Password should be empty");
    }

    private void resolvesStringNote() {
        StringCredentials note = lookupCredentialById("note", StringCredentials.class);
        assertEquals("note", note.getSecret().getPlainText());
    }

    private void resolvesEmptyNote() {
        StringCredentials note = lookupCredentialById("empty note", StringCredentials.class);
        assertTrue(note.getSecret().getPlainText().isEmpty(), "Secure note content should be empty");
    }

    private void resolvesFileNote() throws IOException {
        FileCredentials envFileNote = lookupCredentialById("note.env", FileCredentials.class);
        FileCredentials yamlFileNote = lookupCredentialById("note.yaml", FileCredentials.class);
        String envContent =
                new String(envFileNote.getContent().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String yamlContent =
                new String(yamlFileNote.getContent().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("note", envContent);
        assertEquals("note", yamlContent);
    }

    private void resolvesSshKey() {
        SSHUserPrivateKey key = lookupCredentialById("ssh key", SSHUserPrivateKey.class);
        assertFalse(key.getPrivateKeys().isEmpty(), "SSH private keys list should not be empty");
        String privateKeyContent = key.getPrivateKeys().get(0);
        assertTrue(
                privateKeyContent.contains("-----BEGIN OPENSSH PRIVATE KEY-----"),
                "Private key should contain OpenSSH header");
        assertTrue(
                privateKeyContent.contains("-----END OPENSSH PRIVATE KEY-----"),
                "Private key should contain OpenSSH footer");
        assertEquals("user", key.getUsername());
    }

    private void resolvesSshKeyEmptyComment() {
        SSHUserPrivateKey key = lookupCredentialById("ssh key empty comment", SSHUserPrivateKey.class);
        assertFalse(key.getPrivateKeys().isEmpty(), "SSH private keys list should not be empty");
        String privateKeyContent = key.getPrivateKeys().get(0);
        assertTrue(
                privateKeyContent.contains("-----BEGIN OPENSSH PRIVATE KEY-----"),
                "Private key should contain OpenSSH header");
        assertTrue(
                privateKeyContent.contains("-----END OPENSSH PRIVATE KEY-----"),
                "Private key should contain OpenSSH footer");
        assertEquals(
                System.getProperty("user.name"),
                key.getUsername(),
                "Username should match the system user name because of Jenkins fallback behavior when username is null or empty");
    }

    private void resolvesDuplicates() {
        StringCredentials duplicateNoteA =
                lookupCredentialById("221bfdc5-ed07-4653-9957-b42f0108e2fa", StringCredentials.class);
        StringCredentials duplicateNoteB =
                lookupCredentialById("abaabf94-2b73-4b62-8aa3-b42f0108f9a5", StringCredentials.class);
        assertEquals("note", duplicateNoteA.getSecret().getPlainText());
        assertEquals("note", duplicateNoteB.getSecret().getPlainText());
    }

    private void resolvesBadNames() {
        StringCredentials badNameNote =
                lookupCredentialById("badname~!@#$%^&*()_+{}|:\"<>?`-=[]\\;',./", StringCredentials.class);
        assertEquals("badname", badNameNote.getSecret().getPlainText());
    }

    private void ignoresCardItems() {
        assertThrows(
                NullPointerException.class,
                () -> lookupCredentialById("card", StandardCredentials.class),
                "Card items are not currently suppported");
    }

    private void ignoresIdentityItems() {
        assertThrows(
                NullPointerException.class,
                () -> lookupCredentialById("identity", StandardCredentials.class),
                "Identity items are not currently suppported");
    }

    private void ignoresNonExistentItems() {
        assertThrows(
                NullPointerException.class,
                () -> lookupCredentialById("some-made-up-uuid-that-doesnt-exist", StandardCredentials.class),
                "Non-existent IDs should safely return null and trigger this exception");
    }

    @NonNull
    private static <C extends StandardCredentials> C lookupCredentialById(@NonNull String id, @NonNull Class<C> type) {
        C credential =
                CredentialsProvider.findCredentialByIdInItemGroup(id, type, Jenkins.get(), ACL.SYSTEM2, List.of());
        return Objects.requireNonNull(
                credential, () -> "Required test credential not found in vault: %s".formatted(id));
    }
}
