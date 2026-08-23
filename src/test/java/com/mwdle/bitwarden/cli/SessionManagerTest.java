package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.mwdle.bitwarden.BitwardenConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import java.io.IOException;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

@WithJenkins
@DisplayName("SessionManager")
class SessionManagerTest {

    @BeforeEach
    void setUp() {
        SessionManager.getInstance().invalidateSession();
    }

    private void setupJenkinsCredentials(@NonNull String apiId, @NonNull String masterId) throws Exception {
        BitwardenConfig config = BitwardenConfig.getInstance();
        config.setApiCredentialId(apiId);
        config.setMasterPasswordCredentialId(masterId);

        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, apiId, "API Key", "user", "pass"));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, masterId, "Master Pass", Secret.fromString("master")));
        SystemCredentialsProvider.getInstance().save();
    }

    @Nested
    @DisplayName("State and Invalidation")
    class StateAndInvalidation {

        @Test
        @DisplayName("isSessionValid reflects the cached state")
        void reflectsCachedState(JenkinsRule ignored) throws Exception {
            SessionManager manager = SessionManager.getInstance();

            assertFalse(manager.isSessionValid());

            setupJenkinsCredentials("api", "master");
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(() -> BitwardenCli.unlock(any())).thenReturn(Secret.fromString("token"));

                manager.getSessionKey();
                assertTrue(manager.isSessionValid());

                manager.invalidateSession();
                assertFalse(manager.isSessionValid());
            }
        }
    }

    @Nested
    @DisplayName("getSessionKey()")
    class GetSessionKey {

        @Test
        @DisplayName("throws an IOException if configured credentials are missing from Jenkins")
        void throwsIfCredentialsMissing(JenkinsRule ignored) {
            BitwardenConfig.getInstance().setApiCredentialId("missing-api");
            BitwardenConfig.getInstance().setMasterPasswordCredentialId("missing-master");

            IOException exception = assertThrows(
                    IOException.class, () -> SessionManager.getInstance().getSessionKey());
            assertTrue(exception.getMessage().contains("API Key or Master Password missing"));
        }

        @Test
        @DisplayName("authenticates and caches the session token when credentials are valid")
        void authenticatesAndCachesToken(JenkinsRule ignored) throws Exception {
            setupJenkinsCredentials("api-id", "master-id");

            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(() -> BitwardenCli.unlock(any())).thenReturn(Secret.fromString("secure-token"));

                SessionManager manager = SessionManager.getInstance();

                Secret firstKey = manager.getSessionKey();
                assertEquals("secure-token", firstKey.getPlainText());

                Secret secondKey = manager.getSessionKey();
                assertEquals("secure-token", secondKey.getPlainText());

                cli.verify(BitwardenCli::logout, times(1));
                cli.verify(() -> BitwardenCli.configServer("https://vault.bitwarden.com"), times(1));
                cli.verify(() -> BitwardenCli.login(any()), times(1));
                cli.verify(() -> BitwardenCli.unlock(any()), times(1));
            }
        }

        @Test
        @DisplayName("uses a custom server URL when configured")
        void usesCustomServerUrl(JenkinsRule ignored) throws Exception {
            setupJenkinsCredentials("api-id", "master-id");
            BitwardenConfig.getInstance().setServerUrl("https://vault.example.com");

            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(() -> BitwardenCli.unlock(any())).thenReturn(Secret.fromString("token"));

                SessionManager.getInstance().getSessionKey();

                cli.verify(() -> BitwardenCli.configServer("https://vault.example.com"));
            }
        }
    }
}
