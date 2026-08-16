package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenStatus;
import hudson.ExtensionList;
import hudson.model.ItemGroup;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenSessionManager class.
 * <p>
 * This test suite verifies the logic of the session manager, ensuring it correctly
 * caches, validates, and refreshes the Bitwarden session token in a variety of
 * scenarios. It uses reflection to access the private session token field for
 * state verification, which is a deliberate choice to avoid modifying the
 * production code's access modifiers.
 */
@DisplayName("BitwardenSessionManager")
class BitwardenSessionManagerTest {
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<BitwardenCli> mockedCli;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private BitwardenConfig configMock;

    @Mock
    private Authentication authenticationMock;

    private BitwardenSessionManager manager;
    private Field sessionTokenField;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        mockedJenkins = mockStatic(Jenkins.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedCli = mockStatic(BitwardenCli.class);

        when(Jenkins.get()).thenReturn(jenkinsMock);
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(authenticationMock);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);

        Constructor<BitwardenSessionManager> constructor = BitwardenSessionManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        manager = constructor.newInstance();

        sessionTokenField = BitwardenSessionManager.class.getDeclaredField("sessionToken");
        sessionTokenField.setAccessible(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedConfig.close();
        mockedCli.close();
        closeable.close();
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

            BitwardenStatus unlockedStatus = mock(BitwardenStatus.class);
            when(unlockedStatus.getStatus()).thenReturn("unlocked");
            mockedCli.when(() -> BitwardenCli.status(token)).thenReturn(unlockedStatus);

            // WHEN
            Secret resultToken = manager.getSessionKey();

            // THEN
            assertEquals(token, resultToken);
            mockedCli.verify(() -> BitwardenCli.login(any()), never());
        }

        @Test
        @DisplayName("should refresh token if cached token is invalid (e.g., 'locked')")
        void shouldRefreshTokenWhenCacheIsInvalid() throws Exception {
            // GIVEN
            setupValidCredentials(null);
            Secret initialToken = Secret.fromString("initial-token");
            sessionTokenField.set(manager, initialToken);

            BitwardenStatus lockedStatus = mock(BitwardenStatus.class);
            when(lockedStatus.getStatus()).thenReturn("locked");
            mockedCli.when(() -> BitwardenCli.status(initialToken)).thenReturn(lockedStatus);

            Secret refreshedToken = Secret.fromString("refreshed-token");
            mockedCli
                    .when(() -> BitwardenCli.unlock(any(StringCredentials.class)))
                    .thenReturn(refreshedToken);

            // WHEN
            Secret resultToken = manager.getSessionKey();

            // THEN
            assertEquals(refreshedToken, resultToken);
            mockedCli.verify(() -> BitwardenCli.login(any()), times(1));
        }

        @Test
        @DisplayName("should refresh token if status check fails with IOException")
        void shouldRefreshTokenWhenStatusCheckFails() throws Exception {
            // GIVEN
            setupValidCredentials(null);
            Secret initialToken = Secret.fromString("initial-token");
            sessionTokenField.set(manager, initialToken);

            mockedCli.when(() -> BitwardenCli.status(initialToken)).thenThrow(new IOException("Network error"));

            Secret refreshedToken = Secret.fromString("refreshed-token");
            mockedCli
                    .when(() -> BitwardenCli.unlock(any(StringCredentials.class)))
                    .thenReturn(refreshedToken);

            // WHEN
            Secret resultToken = manager.getSessionKey();

            // THEN
            assertEquals(refreshedToken, resultToken);
            mockedCli.verify(() -> BitwardenCli.login(any()), times(1));
        }

        @Test
        @DisplayName("should clear app data and create new token using default server URL")
        void shouldCreateNewTokenWithDefaultServerUrl() throws Exception {
            // GIVEN
            setupValidCredentials(null);
            Secret newToken = Secret.fromString("new-session-token");
            mockedCli
                    .when(() -> BitwardenCli.unlock(any(StringCredentials.class)))
                    .thenReturn(newToken);

            // WHEN
            Secret resultToken = manager.getSessionKey();

            // THEN
            assertEquals(newToken, resultToken);
            mockedCli.verify(BitwardenCli::logout, times(1));
            mockedCli.verify(BitwardenCli::clearBitwardenAppData, times(1));
            mockedCli.verify(() -> BitwardenCli.configServer("https://vault.bitwarden.com"), times(1));
            mockedCli.verify(() -> BitwardenCli.login(any(StandardUsernamePasswordCredentials.class)), times(1));
        }

        @Test
        @DisplayName("should create new token using custom server URL when configured")
        void shouldCreateNewTokenWithCustomServerUrl() throws Exception {
            // GIVEN
            String customUrl = "https://vault.example.com";
            setupValidCredentials(customUrl);
            Secret newToken = Secret.fromString("custom-url-token");
            mockedCli
                    .when(() -> BitwardenCli.unlock(any(StringCredentials.class)))
                    .thenReturn(newToken);

            // WHEN
            manager.getSessionKey();

            // THEN
            mockedCli.verify(() -> BitwardenCli.configServer(customUrl), times(1));
            mockedCli.verify(() -> BitwardenCli.login(any(StandardUsernamePasswordCredentials.class)), times(1));
        }

        @Test
        @DisplayName("should throw BitwardenAuthenticationException when login fails")
        void shouldThrowExceptionWhenLoginFails() {
            // GIVEN
            setupValidCredentials(null);
            mockedCli
                    .when(() -> BitwardenCli.login(any(StandardUsernamePasswordCredentials.class)))
                    .thenThrow(new AuthenticationException("Invalid API Key", null));

            // WHEN & THEN
            assertThrows(AuthenticationException.class, () -> manager.getSessionKey());
        }

        @Test
        @DisplayName("should throw BitwardenAuthenticationException when unlock fails")
        void shouldThrowExceptionWhenUnlockFails() {
            // GIVEN
            setupValidCredentials(null);
            mockedCli
                    .when(() -> BitwardenCli.unlock(any(StringCredentials.class)))
                    .thenThrow(new AuthenticationException("Invalid Master Password", null));

            // WHEN & THEN
            assertThrows(AuthenticationException.class, () -> manager.getSessionKey());
        }

        @Test
        @DisplayName("should throw IOException when credentials are not found in Jenkins")
        void shouldThrowExceptionWhenCredentialsNotFound() {
            // GIVEN: The credentials provider will return an empty list
            setupCredentialProvider(Collections.emptyList(), Collections.emptyList());

            // WHEN & THEN
            IOException exception = assertThrows(IOException.class, () -> manager.getSessionKey());
            assertTrue(exception.getMessage().contains("Could not find API Key or Master Password credentials"));
        }
    }

    @Nested
    @DisplayName("invalidateSessionToken() method")
    class InvalidateSessionToken {
        @Test
        @DisplayName("should set the cached session token to null")
        void shouldNullifyCachedToken() throws Exception {
            // GIVEN
            sessionTokenField.set(manager, Secret.fromString("a-valid-token"));
            assertNotNull(sessionTokenField.get(manager));

            // WHEN
            manager.invalidateSession();

            // THEN
            assertNull(sessionTokenField.get(manager));
        }
    }

    private void setupCredentialProvider(
            List<StandardUsernamePasswordCredentials> apiKeys, List<StringCredentials> masterPasswords) {
        CredentialsProvider provider = mock(CredentialsProvider.class);
        when(provider.getCredentialsInItemGroup(
                        eq(StandardUsernamePasswordCredentials.class), any(ItemGroup.class), any(), anyList()))
                .thenReturn(apiKeys);
        when(provider.getCredentialsInItemGroup(eq(StringCredentials.class), any(ItemGroup.class), any(), anyList()))
                .thenReturn(masterPasswords);

        @SuppressWarnings("unchecked")
        ExtensionList<CredentialsProvider> extensionList = mock(ExtensionList.class);
        when(extensionList.stream()).thenAnswer(invocation -> Stream.of(provider));
        when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionList);
    }

    @Nested
    @DisplayName("isSessionValid() method")
    class IsSessionValid {
        @Test
        @DisplayName("should return true if token is present and vault is unlocked")
        void shouldReturnTrueWhenUnlocked() throws Exception {
            // GIVEN
            Secret token = Secret.fromString("some-token");
            sessionTokenField.set(manager, token);

            BitwardenStatus unlockedStatus = mock(BitwardenStatus.class);
            when(unlockedStatus.getStatus()).thenReturn("unlocked");
            mockedCli.when(() -> BitwardenCli.status(token)).thenReturn(unlockedStatus);

            // WHEN
            boolean result = manager.isSessionValid();

            // THEN
            assertTrue(result);
        }

        @Test
        @DisplayName("should handle InterruptedException and restore interrupt flag")
        void shouldHandleInterruptedException() throws Exception {
            // GIVEN
            Secret token = Secret.fromString("some-token");
            sessionTokenField.set(manager, token);

            mockedCli.when(() -> BitwardenCli.status(token)).thenThrow(new InterruptedException("Interrupted!"));

            // Ensure the thread is NOT interrupted before the test
            Thread.interrupted();

            // WHEN
            boolean result = manager.isSessionValid();

            // THEN
            assertFalse(result, "isSessionValid should return false when interrupted");
            assertTrue(Thread.interrupted(), "Thread interrupt flag should be set");
        }

        @Test
        @DisplayName("should return false if token is null")
        void shouldReturnFalseWhenTokenIsNull() {
            // GIVEN: manager starts with null sessionToken

            // WHEN
            boolean result = manager.isSessionValid();

            // THEN
            assertFalse(result);
        }
    }

    private void setupValidCredentials(String serverUrl) {
        StandardUsernamePasswordCredentials apiKey = mock(StandardUsernamePasswordCredentials.class);
        when(apiKey.getId()).thenReturn("api-key-id");
        StringCredentials masterPassword = mock(StringCredentials.class);
        when(masterPassword.getId()).thenReturn("master-password-id");

        setupCredentialProvider(Collections.singletonList(apiKey), Collections.singletonList(masterPassword));

        when(configMock.getApiCredentialId()).thenReturn("api-key-id");
        when(configMock.getMasterPasswordCredentialId()).thenReturn("master-password-id");
        when(configMock.getServerUrl()).thenReturn(serverUrl);
    }
}
