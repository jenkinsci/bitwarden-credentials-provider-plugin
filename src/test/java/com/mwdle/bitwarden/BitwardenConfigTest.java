package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.CliManager;
import com.mwdle.bitwarden.cli.SessionManager;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.io.IOException;

import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

/**
 * Integration tests for {@link BitwardenConfig}: value normalization, defaults, and the UI action methods on paths
 * that do not touch a real Bitwarden CLI or vault.
 */
@WithJenkins
@DisplayName("BitwardenConfig")
class BitwardenConfigTest {

    private static BitwardenConfig config() {
        return BitwardenConfig.getInstance();
    }

    @Nested
    @DisplayName("value normalization")
    class Normalization {

        @Test
        @DisplayName("strips surrounding whitespace on string settings")
        void stripsWhitespace(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setServerUrl("  https://vault.example.com  ");
            config.setApiCredentialId("  api  ");
            config.setMasterPasswordCredentialId("  master  ");

            assertEquals("https://vault.example.com", config.getServerUrl());
            assertEquals("api", config.getApiCredentialId());
            assertEquals("master", config.getMasterPasswordCredentialId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("normalizes blank credential ids to null")
        void blankBecomesNull(String blank, JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setApiCredentialId(blank);

            assertNull(config.getApiCredentialId());
        }

        @Test
        @DisplayName("falls back to the default server URL when unset")
        void defaultServerUrl(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setServerUrl(null);

            assertEquals("https://vault.bitwarden.com", config.getServerUrl());
        }

        @ParameterizedTest
        @CsvSource({"0, 5", "-3, 5", "10, 10", "1, 1"})
        @DisplayName("defaults non-positive cache durations to 5 minutes")
        void cacheDurationDefaulting(int input, int expected, JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setCacheDuration(input);

            assertEquals(expected, config.getCacheDuration());
        }
    }

    @Nested
    @DisplayName("isConfigured")
    class IsConfigured {

        @ParameterizedTest
        @CsvSource({
            ", , false",
            "api, , false",
            ", master, false",
            "api, master, true",
        })
        @DisplayName("requires both an API key and a master password credential id")
        void requiresBothCredentials(String apiId, String masterId, boolean expected, JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setApiCredentialId(apiId);
            config.setMasterPasswordCredentialId(masterId);

            assertEquals(expected, config.isConfigured());
        }
    }

    @Nested
    @DisplayName("hasFileCredentialSuffix")
    class FileSuffixes {

        @Test
        @DisplayName("matches names ending with a configured, whitespace-tolerant suffix")
        void matchesConfiguredSuffix(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setFileCredentialSuffixes(" .env , .yaml ");

            assertTrue(config.hasFileCredentialSuffix("production.env"));
            assertTrue(config.hasFileCredentialSuffix("stack.yaml"));
            assertFalse(config.hasFileCredentialSuffix("notes.txt"));
        }

        @Test
        @DisplayName("matches nothing when no suffixes are configured")
        void noSuffixesConfigured(JenkinsRule ignored) {
            assertFalse(config().hasFileCredentialSuffix("production.env"));
        }
    }

    @Nested
    @DisplayName("action methods")
    @SuppressWarnings("ResultOfMethodCallIgnored")
    class Actions {

        @Test
        @DisplayName("doVerifySession warns when the plugin is not configured")
        void verifySessionUnconfigured(JenkinsRule ignored) {
            assertEquals(FormValidation.Kind.WARNING, config().doVerifySession().kind);
        }

        @Test
        @DisplayName("doVerifySession reports OK when a session is active")
        void verifySessionActive(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setApiCredentialId("api");
            config.setMasterPasswordCredentialId("master");

            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.isSessionValid()).thenReturn(true);
            try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
                sessions.when(SessionManager::getInstance).thenReturn(sessionManager);

                assertEquals(FormValidation.Kind.OK, config.doVerifySession().kind);
            }
        }

        @Test
        @DisplayName("doVerifySession warns when no session is active")
        void verifySessionInactive(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setApiCredentialId("api");
            config.setMasterPasswordCredentialId("master");

            SessionManager sessionManager = mock(SessionManager.class);
            when(sessionManager.isSessionValid()).thenReturn(false);
            try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
                sessions.when(SessionManager::getInstance).thenReturn(sessionManager);

                assertEquals(FormValidation.Kind.WARNING, config.doVerifySession().kind);
            }
        }

        @Test
        @DisplayName("doSyncVault warns when the plugin is not configured")
        void syncVaultUnconfigured(JenkinsRule ignored) {
            assertEquals(FormValidation.Kind.WARNING, config().doSyncVault().kind);
        }

        @Test
        @DisplayName("doCheckCliVersion reports the version on success")
        void checkCliVersionOk(JenkinsRule ignored) {
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(BitwardenCli::version).thenReturn("2026.7.0");

                FormValidation result = config().doCheckCliVersion();

                assertEquals(FormValidation.Kind.OK, result.kind);
                assertTrue(result.getMessage().contains("2026.7.0"));
            }
        }

        @Test
        @DisplayName("doCheckCliVersion reports an error on failure")
        void checkCliVersionError(JenkinsRule ignored) {
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(BitwardenCli::version).thenThrow(new IOException("CLI not found"));

                assertEquals(FormValidation.Kind.ERROR, config().doCheckCliVersion().kind);
            }
        }

        @Test
        @DisplayName("doUpdateCli warns when a manual CLI path is configured")
        void updateCliManualPath(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setCliExecutablePath("/usr/local/bin/bw");

            assertEquals(FormValidation.Kind.WARNING, config.doUpdateCli().kind);
        }

        @Test
        @DisplayName("doSyncVault does not fail when the plugin is configured")
        void syncVaultConfigured(JenkinsRule ignored) {
            BitwardenConfig config = config();
            config.setApiCredentialId("api");
            config.setMasterPasswordCredentialId("master");
            assertEquals(FormValidation.Kind.OK, config.doSyncVault().kind);
        }

        @Test
        @DisplayName("doCheckCliVersion reports an error on InterruptedException")
        void checkCliVersionInterrupted(JenkinsRule ignored) {
            try (MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(BitwardenCli::version).thenThrow(new InterruptedException("Interrupted"));

                assertEquals(FormValidation.Kind.ERROR, config().doCheckCliVersion().kind);
                assertTrue(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("doUpdateCli succeeds when no manual path is configured")
        void updateCliSuccess(JenkinsRule ignored) {
            try (MockedStatic<CliManager> cliManager = mockStatic(CliManager.class);
                 MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
                cli.when(BitwardenCli::version).thenReturn("2026.7.0");

                assertEquals(FormValidation.Kind.OK, config().doUpdateCli().kind);

                cliManager.verify(CliManager::updateExecutable, times(1));
            }
        }

        @Test
        @DisplayName("doUpdateCli reports an error on IOException")
        void updateCliIoException(JenkinsRule ignored) {
            try (MockedStatic<CliManager> cliManager = mockStatic(CliManager.class)) {
                cliManager.when(CliManager::updateExecutable).thenThrow(new IOException("Download failed"));

                assertEquals(FormValidation.Kind.ERROR, config().doUpdateCli().kind);
            }
        }

        @Test
        @DisplayName("doUpdateCli reports an error on InterruptedException")
        void updateCliInterrupted(JenkinsRule ignored) {
            try (MockedStatic<CliManager> cliManager = mockStatic(CliManager.class)) {
                cliManager.when(CliManager::updateExecutable).thenThrow(new InterruptedException("Interrupted"));

                assertEquals(FormValidation.Kind.ERROR, config().doUpdateCli().kind);
                assertTrue(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("list box items are restricted to empty value for non-admin users")
        void credentialsListBoxRespectsPermissions(JenkinsRule r) throws Exception {
            BitwardenConfig config = config();

            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            r.jenkins.setAuthorizationStrategy(new hudson.security.FullControlOnceLoggedInAuthorizationStrategy());

            UsernamePasswordCredentialsImpl sampleCred =
                    new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL, "test-cred-id", "Test Credential", "admin", "password");

            SystemCredentialsProvider.getInstance().getCredentials().add(sampleCred);
            SystemCredentialsProvider.getInstance().save();

            try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
                ListBoxModel apiModel = config.doFillApiCredentialIdItems(r.jenkins, "");
                ListBoxModel masterModel = config.doFillMasterPasswordCredentialIdItems(r.jenkins, "");

                assertEquals(1, apiModel.size(), "Non-admins should only see an empty value option");
                assertEquals("", apiModel.get(0).value);

                assertEquals(1, masterModel.size(), "Non-admins should only see an empty value option");
                assertEquals("", masterModel.get(0).value);
            }
        }

        @Test
        @DisplayName("list box items include matching credentials for admin users")
        void credentialsListBoxIncludesCredentialsForAdmin(JenkinsRule r) throws Exception {
            BitwardenConfig config = config();

            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            r.jenkins.setAuthorizationStrategy(new hudson.security.FullControlOnceLoggedInAuthorizationStrategy());

            UsernamePasswordCredentialsImpl sampleCred =
                    new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL, "test-cred-id", "Test Credential", "admin", "password");

            SystemCredentialsProvider.getInstance().getCredentials().add(sampleCred);
            SystemCredentialsProvider.getInstance().save();

            ListBoxModel apiModel = config.doFillApiCredentialIdItems(r.jenkins, "");

            assertTrue(apiModel.size() >= 2, "Admin should see the empty value and matching credentials");
            boolean found = apiModel.stream().anyMatch(item -> "test-cred-id".equals(item.value));
            assertTrue(found, "ListBox model should contain the seeded test credential ID");
        }
    }
}
