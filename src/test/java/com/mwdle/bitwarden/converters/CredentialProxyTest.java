package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import hudson.util.Secret;
import java.io.IOException;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

/**
 * Verifies the lazy-loading behaviour of {@link CredentialProxy}: non-secret data is served from memory, while secret
 * resolution defers to the CLI exactly once and surfaces failures as {@link IllegalStateException}.
 */
@WithJenkins
@DisplayName("CredentialProxy")
class CredentialProxyTest {

    private static BitwardenItemMetadata metadata(String id, String name) {
        return new BitwardenItemMetadata(id, name, BitwardenItemType.SECURE_NOTE);
    }

    private static BitwardenItem note() {
        return new BitwardenItem(
                "item-id",
                "Note",
                BitwardenItemType.SECURE_NOTE,
                Secret.fromString("my-secret-value"),
                null,
                null);
    }

    @Nested
    @DisplayName("fast path (no CLI)")
    class FastPath {

        private final SecureNoteStringConverter stringConverter = new SecureNoteStringConverter();
        private final SecureNoteFileConverter fileConverter = new SecureNoteFileConverter();

        @Test
        @DisplayName("serves id and scope from memory without contacting the CLI")
        void servesIdAndScope(JenkinsRule ignored) {
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                StandardCredentials proxy = stringConverter.createProxy("UniqueName", metadata("uuid-1", "UniqueName"));

                assertEquals("UniqueName", proxy.getId());
                assertEquals(CredentialsScope.GLOBAL, proxy.getScope());
                cli.verifyNoInteractions();
            }
        }

        @Test
        @DisplayName("formats the description for a uniquely-named item")
        void describesUniqueItem(JenkinsRule ignored) {
            StandardCredentials proxy = stringConverter.createProxy("UniqueName", metadata("uuid-1", "UniqueName"));

            assertEquals("UniqueName (BW ID: uuid-1)", proxy.getDescription());
        }

        @Test
        @DisplayName("marks the description of a non-uniquely-named item")
        void describesDuplicateItem(JenkinsRule ignored) {
            StandardCredentials proxy = stringConverter.createProxy("uuid-1", metadata("uuid-1", "DuplicateName"));

            assertEquals("DuplicateName (BW ID: uuid-1, non-unique name)", proxy.getDescription());
        }

        @Test
        @DisplayName("uses an id-only description for file credentials")
        void describesFileCredential(JenkinsRule ignored) {
            StandardCredentials proxy = fileConverter.createProxy("config.env", metadata("uuid-1", "config.env"));

            assertEquals("BW ID: uuid-1", proxy.getDescription());
        }

        @Test
        @DisplayName("serves filename from memory without contacting the CLI and uses the item name as the file name")
        void servesFilename(JenkinsRule ignored) {
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                BitwardenConfig.getInstance().setFileCredentialSuffixes(".env");
                FileCredentials proxy = fileConverter.createProxy("UniqueName", metadata("uuid-1", "UniqueName"));

                assertEquals("UniqueName", proxy.getFileName());
                assertEquals(CredentialsScope.GLOBAL, proxy.getScope());
                cli.verifyNoInteractions();
            }
        }
    }

    @Nested
    @DisplayName("secret resolution")
    @SuppressWarnings("ResultOfMethodCallIgnored")
    class SecretResolution {

        private final SecureNoteStringConverter converter = new SecureNoteStringConverter();

        @Test
        @DisplayName("fetches the item once and caches the resolved credential")
        void resolvesAndCaches(JenkinsRule ignored) throws Exception {
            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.getSessionKey()).thenReturn(Secret.fromString("session"));

            try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class);
                    MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                sessions.when(SessionManager::getInstance).thenReturn(sessionManager);
                cli.when(() -> BitwardenCli.getItem(any(), eq("item-id"))).thenReturn(note());

                StringCredentials proxy = converter.createProxy("cred-id", metadata("item-id", "Note"));

                assertEquals("my-secret-value", proxy.getSecret().getPlainText());
                assertSame(proxy.getSecret(), proxy.getSecret(), "second read should reuse the cached credential");
                cli.verify(() -> BitwardenCli.getItem(any(), eq("item-id")), times(1));
            }
        }

        @Test
        @DisplayName("wraps a CLI failure in an IllegalStateException")
        void wrapsCliFailure(JenkinsRule ignored) throws Exception {
            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.getSessionKey()).thenReturn(Secret.fromString("session"));

            try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class);
                    MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                sessions.when(SessionManager::getInstance).thenReturn(sessionManager);
                cli.when(() -> BitwardenCli.getItem(any(), eq("item-id"))).thenThrow(new IOException("CLI boom"));

                StringCredentials proxy = converter.createProxy("cred-id", metadata("item-id", "Note"));

                assertThrows(IllegalStateException.class, proxy::getSecret);
            }
        }
    }
}
