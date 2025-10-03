package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenStatus;
import hudson.ExtensionList;
import hudson.model.ItemGroup;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenSessionManager class.
 * <p>
 * This test suite verifies the logic of the session manager, ensuring it correctly
 * caches, validates, and refreshes the Bitwarden session token in a variety of
 * scenarios.
 */
@DisplayName("BitwardenSessionManager")
class BitwardenSessionManagerTest {
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<BitwardenCLI> mockedCli;
    private Jenkins jenkinsMock;
    private BitwardenConfig configMock;
    private BitwardenSessionManager manager;
    private Field sessionTokenField;

    @BeforeEach
    void setUp() throws Exception {
        mockedJenkins = mockStatic(Jenkins.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedCli = mockStatic(BitwardenCLI.class);

        jenkinsMock = mock(Jenkins.class);
        configMock = mock(BitwardenConfig.class);

        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);

        manager = new BitwardenSessionManager();

        // Make the private sessionToken field accessible for assertions
        sessionTokenField = BitwardenSessionManager.class.getDeclaredField("sessionToken");
        sessionTokenField.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        mockedJenkins.close();
        mockedConfig.close();
        mockedCli.close();
    }

    @Nested
    @DisplayName("getSessionToken() method")
    class GetSessionToken {
        @Test
        @DisplayName("should return cached token if it is valid")
        void shouldReturnCachedTokenWhenValid() throws Exception {
            // GIVEN: A valid token is already cached
            Secret token = Secret.fromString("valid-cached-token");
            sessionTokenField.set(manager, token);

            // And the CLI status check confirms it is "unlocked"
            BitwardenStatus unlockedStatus = mock(BitwardenStatus.class);
            when(unlockedStatus.getStatus()).thenReturn("unlocked");
            mockedCli.when(() -> BitwardenCLI.status(token)).thenReturn(unlockedStatus);

            // WHEN
            Secret resultToken = manager.getSessionToken();

            // THEN
            assertEquals(token, resultToken, "Should have returned the cached token.");
            mockedCli.verify(() -> BitwardenCLI.login(any()), never());
            mockedCli.verify(() -> BitwardenCLI.unlock(any()), never());
        }

        @Test
        @DisplayName("should create new token using default server URL when config is null or empty")
        void shouldCreateNewTokenWithDefaultServerUrl() throws Exception {
            // GIVEN: Valid credentials are set up, but server URL is null
            setupValidCredentials(null);
            Secret newToken = Secret.fromString("new-session-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(newToken);

            // WHEN
            Secret resultToken = manager.getSessionToken();

            // THEN
            assertEquals(newToken, resultToken, "The new token from the unlock command should be returned.");
            // Verify the full login sequence was performed with the default URL
            mockedCli.verify(BitwardenCLI::logout, times(1));
            mockedCli.verify(() -> BitwardenCLI.configServer("https://vault.bitwarden.com"), times(1));
            mockedCli.verify(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)), times(1));
            mockedCli.verify(() -> BitwardenCLI.unlock(any(StringCredentials.class)), times(1));
        }

        @Test
        @DisplayName("should create new token using custom server URL when configured")
        void shouldCreateNewTokenWithCustomServerUrl() throws Exception {
            // GIVEN: Valid credentials and a custom server URL are configured
            String customUrl = "https://vault.example.com";
            setupValidCredentials(customUrl);
            Secret newToken = Secret.fromString("custom-url-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(newToken);

            // WHEN
            manager.getSessionToken();

            // THEN: Verify the full login sequence was performed with the custom URL
            mockedCli.verify(BitwardenCLI::logout, times(1));
            mockedCli.verify(() -> BitwardenCLI.configServer(customUrl), times(1));
            mockedCli.verify(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)), times(1));
            mockedCli.verify(() -> BitwardenCLI.unlock(any(StringCredentials.class)), times(1));
        }

        @Test
        @DisplayName("should refresh token if cached token is invalid (e.g., 'locked')")
        void shouldRefreshTokenWhenCacheIsInvalid() throws Exception {
            // GIVEN: A token is cached, but it is no longer valid
            setupValidCredentials(null);
            Secret initialToken = Secret.fromString("initial-token");
            sessionTokenField.set(manager, initialToken);

            // The first status check shows an invalid state
            BitwardenStatus lockedStatus = mock(BitwardenStatus.class);
            when(lockedStatus.getStatus()).thenReturn("locked");
            mockedCli.when(() -> BitwardenCLI.status(initialToken)).thenReturn(lockedStatus);

            // A subsequent call to unlock will return a new, valid token
            Secret refreshedToken = Secret.fromString("refreshed-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(refreshedToken);

            // WHEN
            Secret resultToken = manager.getSessionToken();

            // THEN
            assertEquals(refreshedToken, resultToken, "Should return the new, refreshed token.");
            // Verify that the login process was triggered after the failed status check
            mockedCli.verify(BitwardenCLI::logout, times(1));
            mockedCli.verify(() -> BitwardenCLI.login(any()), times(1));
            mockedCli.verify(() -> BitwardenCLI.unlock(any()), times(1));
        }

        @Test
        @DisplayName("should throw BitwardenAuthenticationException when login fails")
        void shouldThrowExceptionWhenLoginFails() {
            // GIVEN: Credentials are set up
            setupValidCredentials(null);
            // And the CLI login command will fail
            mockedCli
                    .when(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)))
                    .thenThrow(new BitwardenAuthenticationException("Invalid API Key", null));

            // WHEN & THEN
            BitwardenAuthenticationException exception =
                    assertThrows(BitwardenAuthenticationException.class, () -> manager.getSessionToken());
            assertTrue(exception.getMessage().contains("Invalid API Key"));
        }

        @Test
        @DisplayName("should throw BitwardenAuthenticationException when unlock fails")
        void shouldThrowExceptionWhenUnlockFails() {
            // GIVEN: Credentials are set up
            setupValidCredentials(null);
            // And the CLI unlock command will fail
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenThrow(new BitwardenAuthenticationException("Invalid Master Password", null));

            // WHEN & THEN
            BitwardenAuthenticationException exception =
                    assertThrows(BitwardenAuthenticationException.class, () -> manager.getSessionToken());
            assertTrue(exception.getMessage().contains("Invalid Master Password"));
        }

        @Test
        @DisplayName("should throw IOException when credentials are not found in Jenkins")
        void shouldThrowExceptionWhenCredentialsNotFound() {
            // GIVEN: The credentials provider will return an empty list
            CredentialsProvider provider = mock(CredentialsProvider.class);
            when(provider.getCredentialsInItemGroup(any(), any(), any(), anyList()))
                    .thenReturn(Collections.emptyList());
            @SuppressWarnings("unchecked")
            ExtensionList<CredentialsProvider> extensionList = mock(ExtensionList.class);
            when(extensionList.stream()).thenAnswer(invocation -> Stream.of(provider));
            when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionList);

            // WHEN & THEN
            IOException exception = assertThrows(IOException.class, () -> manager.getSessionToken());
            assertTrue(exception.getMessage().contains("Could not find API Key or Master Password credentials"));
        }
    }

    @Nested
    @DisplayName("invalidateSessionToken() method")
    class InvalidateSessionToken {
        @Test
        @DisplayName("should set the cached session token to null")
        void shouldNullifyCachedToken() throws Exception {
            // GIVEN: a token is currently cached in the manager
            Secret token = Secret.fromString("a-valid-token");
            sessionTokenField.set(manager, token);
            assertNotNull(sessionTokenField.get(manager), "Token should be cached initially.");

            // WHEN
            manager.invalidateSessionToken();

            // THEN
            assertNull(sessionTokenField.get(manager), "Token should be null after invalidation.");
        }
    }

    /**
     * Helper method to set up valid API Key and Master Password credentials,
     * simulating how they would be resolved from Jenkins.
     *
     * @param serverUrl The Bitwarden server URL to configure, or null to use the default.
     */
    private void setupValidCredentials(String serverUrl) {
        // Mock the credentials that will be "found" in Jenkins
        StandardUsernamePasswordCredentials apiKey = mock(StandardUsernamePasswordCredentials.class);
        when(apiKey.getId()).thenReturn("api-key-id");
        StringCredentials masterPassword = mock(StringCredentials.class);
        when(masterPassword.getId()).thenReturn("master-password-id");

        // Mock the CredentialsProvider to return our credentials
        CredentialsProvider provider = mock(CredentialsProvider.class);
        when(provider.getCredentialsInItemGroup(
                        eq(StandardUsernamePasswordCredentials.class), any(ItemGroup.class), any(), anyList()))
                .thenReturn(Collections.singletonList(apiKey));
        when(provider.getCredentialsInItemGroup(eq(StringCredentials.class), any(ItemGroup.class), any(), anyList()))
                .thenReturn(Collections.singletonList(masterPassword));

        // Mock the Jenkins extension list to return our provider
        @SuppressWarnings("unchecked")
        ExtensionList<CredentialsProvider> extensionList = mock(ExtensionList.class);
        when(extensionList.stream()).thenAnswer(invocation -> Stream.of(provider));
        when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionList);
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(mock(Authentication.class));

        // Mock the BitwardenConfig to return the IDs of the credentials to look for
        when(configMock.getApiCredentialId()).thenReturn("api-key-id");
        when(configMock.getMasterPasswordCredentialId()).thenReturn("master-password-id");
        when(configMock.getServerUrl()).thenReturn(serverUrl);
    }
}
