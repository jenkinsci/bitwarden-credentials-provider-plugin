package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenSessionManager class.
 */
@DisplayName("BitwardenSessionManager")
public class BitwardenSessionManagerTest {

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<BitwardenCLI> mockedCli;
    private Jenkins jenkinsMock;
    private BitwardenConfig configMock;

    private BitwardenSessionManager manager;

    @BeforeEach
    void setUp() {
        mockedJenkins = mockStatic(Jenkins.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedCli = mockStatic(BitwardenCLI.class);

        jenkinsMock = mock(Jenkins.class);
        configMock = mock(BitwardenConfig.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);

        manager = new BitwardenSessionManager();
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
            Secret token = Secret.fromString("valid-cached-token");
            Field sessionTokenField = BitwardenSessionManager.class.getDeclaredField("sessionToken");
            sessionTokenField.setAccessible(true);
            sessionTokenField.set(manager, token);

            BitwardenStatus unlockedStatus = mock(BitwardenStatus.class);
            when(unlockedStatus.getStatus()).thenReturn("unlocked");
            mockedCli.when(() -> BitwardenCLI.status(token)).thenReturn(unlockedStatus);

            Secret resultToken = manager.getSessionToken();

            assertEquals(token, resultToken, "Should have returned the cached token.");

            // Verify that no login/unlock operations were attempted.
            mockedCli.verify(() -> BitwardenCLI.login(any()), never());
            mockedCli.verify(() -> BitwardenCLI.unlock(any()), never());
        }

        @Test
        @DisplayName("should create new token if cache is empty")
        void shouldCreateNewTokenWhenCacheIsEmpty() throws Exception {
            setupValidCredentials();

            Secret newToken = Secret.fromString("new-session-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(newToken);

            Secret resultToken = manager.getSessionToken();

            assertEquals(newToken, resultToken, "The new token from the unlock command should be returned.");

            mockedCli.verify(BitwardenCLI::logout);
            mockedCli.verify(() -> BitwardenCLI.configServer(anyString()));
            mockedCli.verify(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)));
            mockedCli.verify(() -> BitwardenCLI.unlock(any(StringCredentials.class)));
        }

        @Test
        @DisplayName("should refresh token if cache is invalid")
        void shouldRefreshTokenWhenCacheIsInvalid() throws Exception {
            setupValidCredentials();
            Secret initialToken = Secret.fromString("initial-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(initialToken);
            assertEquals(initialToken, manager.getSessionToken(), "Should get the initial token successfully.");

            BitwardenStatus lockedStatus = mock(BitwardenStatus.class);
            when(lockedStatus.getStatus()).thenReturn("locked");
            mockedCli.when(() -> BitwardenCLI.status(initialToken)).thenReturn(lockedStatus);

            Secret refreshedToken = Secret.fromString("refreshed-token");
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenReturn(refreshedToken);

            Secret resultToken = manager.getSessionToken();

            assertEquals(refreshedToken, resultToken, "Should return the new, refreshed token.");

            mockedCli.verify(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)), times(2));
            mockedCli.verify(() -> BitwardenCLI.unlock(any(StringCredentials.class)), times(2));
        }

        @Test
        @DisplayName("should throw exception when login fails")
        void shouldThrowExceptionWhenLoginFails() {
            // Setup credentials so the login process starts
            setupValidCredentials();

            // Tell the mock CLI to throw an error when login is attempted
            mockedCli
                    .when(() -> BitwardenCLI.login(any(StandardUsernamePasswordCredentials.class)))
                    .thenThrow(new BitwardenAuthenticationException("Invalid API Key", new RuntimeException()));

            BitwardenAuthenticationException exception = assertThrows(
                    BitwardenAuthenticationException.class,
                    () -> manager.getSessionToken(),
                    "Should throw an exception when login fails.");
            assertTrue(
                    exception.getMessage().contains("Invalid API Key"),
                    "The exception message should indicate a login failure.");
        }

        @Test
        @DisplayName("should throw exception when unlock fails")
        void shouldThrowExceptionWhenUnlockFails() {
            setupValidCredentials();

            // Tell the mock CLI to succeed on login but fail on unlock
            mockedCli
                    .when(() -> BitwardenCLI.unlock(any(StringCredentials.class)))
                    .thenThrow(new BitwardenAuthenticationException("Invalid Master Password", new RuntimeException()));

            BitwardenAuthenticationException exception = assertThrows(
                    BitwardenAuthenticationException.class,
                    () -> manager.getSessionToken(),
                    "Should throw an exception when unlock fails.");
            assertTrue(
                    exception.getMessage().contains("Invalid Master Password"),
                    "The exception message should indicate an unlock failure.");
        }

        @Test
        @DisplayName("should throw exception when credentials are not found")
        void shouldThrowExceptionWhenCredentialsNotFound() {
            CredentialsProvider provider = mock(CredentialsProvider.class);
            when(provider.getCredentialsInItemGroup(any(), any(), any(), anyList()))
                    .thenReturn(Collections.emptyList());

            @SuppressWarnings("unchecked")
            ExtensionList<CredentialsProvider> extensionList = mock(ExtensionList.class);
            when(extensionList.stream()).thenAnswer(invocation -> Stream.of(provider));
            when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionList);

            IOException exception = assertThrows(
                    IOException.class,
                    () -> manager.getSessionToken(),
                    "Should throw an exception when credentials cannot be found.");
            assertTrue(
                    exception.getMessage().contains("Could not find API Key or Master Password credentials"),
                    "The exception message should indicate missing credentials.");
        }

        /**
         * Helper method to set up valid API Key and Master Password credentials.
         */
        private void setupValidCredentials() {
            StandardUsernamePasswordCredentials apiKey = mock(StandardUsernamePasswordCredentials.class);
            when(apiKey.getId()).thenReturn("api-key-id");
            StringCredentials masterPassword = mock(StringCredentials.class);
            when(masterPassword.getId()).thenReturn("master-password-id");

            CredentialsProvider provider = mock(CredentialsProvider.class);
            when(provider.getCredentialsInItemGroup(
                            eq(StandardUsernamePasswordCredentials.class),
                            any(ItemGroup.class),
                            any(Authentication.class),
                            anyList()))
                    .thenReturn(Collections.singletonList(apiKey));
            when(provider.getCredentialsInItemGroup(
                            eq(StringCredentials.class), any(ItemGroup.class), any(Authentication.class), anyList()))
                    .thenReturn(Collections.singletonList(masterPassword));

            @SuppressWarnings("unchecked")
            ExtensionList<CredentialsProvider> extensionList = mock(ExtensionList.class);
            when(extensionList.stream()).thenAnswer(invocation -> Stream.of(provider));
            when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionList);

            mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(mock(Authentication.class));
            when(configMock.getApiCredentialId()).thenReturn("api-key-id");
            when(configMock.getMasterPasswordCredentialId()).thenReturn("master-password-id");
        }
    }
}
