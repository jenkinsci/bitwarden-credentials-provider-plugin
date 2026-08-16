package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.model.BitwardenItem;
import hudson.model.Descriptor;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the CredentialProxy class.
 * <p>
 * This test suite verifies the core lazy-loading logic of the proxy. It ensures that
 * non-secret methods are handled instantly, while secret-resolving methods trigger the
 * CLI call exactly once and then use a cached result.
 */
@DisplayName("CredentialProxy")
class CredentialProxyTest {

    @Mock
    private BitwardenSessionManager bitwardenSessionManagerMock;

    private Descriptor<?> stringDescriptor;
    private Descriptor<?> fileDescriptor;

    private MockedStatic<BitwardenSessionManager> mockedSessionManager;
    private MockedStatic<BitwardenCli> mockedCli;
    private MockedStatic<CredentialConverter> mockedConverter;
    private MockedStatic<Messages> mockedMessages;

    private AutoCloseable closeable;
    private StringCredentials testProxy;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        closeable = MockitoAnnotations.openMocks(this);

        mockedSessionManager = mockStatic(BitwardenSessionManager.class);
        when(BitwardenSessionManager.getInstance()).thenReturn(bitwardenSessionManagerMock);
        doReturn(Secret.fromString("test-session-token"))
                .when(bitwardenSessionManagerMock)
                .getSessionKey();

        mockedCli = mockStatic(BitwardenCli.class);
        mockedConverter = mockStatic(CredentialConverter.class);
        mockedMessages = mockStatic(Messages.class);

        when(Messages.description_idLabel()).thenReturn("BW ID:");
        when(Messages.description_nonUniqueLabel()).thenReturn("non-unique name");

        // Create a default proxy for general use in tests
        CredentialProxy handler = new CredentialProxy("cred-id", "item-id", "Item Name", StringCredentialsImpl.class);
        testProxy = (StringCredentials) Proxy.newProxyInstance(
                StringCredentials.class.getClassLoader(), new Class<?>[] {StringCredentials.class}, handler);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedSessionManager.close();
        mockedCli.close();
        mockedConverter.close();
        mockedMessages.close();
        closeable.close();
    }

    @Nested
    @DisplayName("Fast-Path (Non-Secret) Method Invocations")
    class FastPath {
        @Test
        @DisplayName("should return metadata instantly without calling CLI")
        void shouldReturnMetadataWithoutCliCall() {
            // WHEN
            assertEquals("cred-id", testProxy.getId());
            assertEquals(stringDescriptor, testProxy.getDescriptor());
            assertEquals("BitwardenCredentialProxy(itemId=item-id)", testProxy.toString());
            assertEquals("item-id".hashCode(), testProxy.hashCode());

            // THEN: Verify that no expensive operations were performed
            mockedCli.verify(() -> BitwardenCli.getItem(any(), any()), never());
        }

        @Test
        @DisplayName("should handle security metadata methods")
        void shouldHandleSecurityMetadata() throws Throwable {
            // GIVEN
            CredentialProxy handler =
                    new CredentialProxy("cred-id", "item-id", "Item Name", StringCredentialsImpl.class);
            Method isUsernameSecret = StandardUsernameCredentials.class.getMethod("isUsernameSecret");
            Method getPassphrase = SSHUserPrivateKey.class.getMethod("getPassphrase");

            // WHEN
            boolean isSecret = (boolean) handler.invoke(null, isUsernameSecret, null);
            Secret passphrase = (Secret) handler.invoke(null, getPassphrase, null);

            // THEN
            assertTrue(isSecret);
            assertEquals("", passphrase.getPlainText());
            mockedCli.verify(() -> BitwardenCli.getItem(any(), any()), never());
        }
    }

    @Nested
    @DisplayName("Slow-Path (Secret) Method Invocation and Caching")
    class SlowPath {
        @Test
        @DisplayName("should call CLI exactly once and cache the result")
        void shouldCallCliOnceAndCacheResult() {
            // GIVEN
            BitwardenItem fullItem = mock(BitwardenItem.class);
            mockedCli.when(() -> BitwardenCli.getItem(any(), eq("item-id"))).thenReturn(fullItem);

            CredentialConverter converter = mock(CredentialConverter.class);
            mockedConverter
                    .when(() -> CredentialConverter.getConverter(fullItem))
                    .thenReturn(converter);

            StringCredentialsImpl resolvedCredential = mock(StringCredentialsImpl.class);
            when(resolvedCredential.getSecret()).thenReturn(Secret.fromString("my-secret-value"));
            when(converter.convert(any(), any(), any(), eq(fullItem))).thenReturn(resolvedCredential);

            // WHEN: First call to a secret method
            Secret firstResult = testProxy.getSecret();

            // THEN: Verify the full resolution path was followed
            assertEquals("my-secret-value", firstResult.getPlainText());
            mockedCli.verify(() -> BitwardenCli.getItem(any(), eq("item-id")), times(1));
            mockedConverter.verify(() -> CredentialConverter.getConverter(fullItem), times(1));
            verify(converter, times(1)).convert(any(), any(), any(), eq(fullItem));

            // WHEN: Second call to a secret method
            Secret secondResult = testProxy.getSecret();

            // THEN: The result should be the same, and no further CLI calls should be made
            assertEquals("my-secret-value", secondResult.getPlainText());
            mockedCli.verify(() -> BitwardenCli.getItem(any(), eq("item-id")), times(1)); // Still 1
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {
        @Test
        @DisplayName("should re-throw IOException if CLI fails")
        void shouldThrowIOExceptionOnCliFailure() {
            // GIVEN
            mockedCli.when(() -> BitwardenCli.getItem(any(), eq("item-id"))).thenThrow(new IOException("CLI error"));

            // WHEN & THEN
            UndeclaredThrowableException exception =
                    assertThrows(UndeclaredThrowableException.class, testProxy::getSecret);
            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("CLI error"));
        }

        @Test
        @DisplayName("should throw IOException if no converter is found")
        void shouldThrowIOExceptionWhenNoConverterFound() {
            // GIVEN
            BitwardenItem fullItem = mock(BitwardenItem.class);
            mockedCli.when(() -> BitwardenCli.getItem(any(), eq("item-id"))).thenReturn(fullItem);
            mockedConverter
                    .when(() -> CredentialConverter.getConverter(fullItem))
                    .thenReturn(null); // No converter

            // WHEN & THEN
            UndeclaredThrowableException exception =
                    assertThrows(UndeclaredThrowableException.class, testProxy::getSecret);
            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("No suitable converter found"));
        }
    }

    @Nested
    @DisplayName("Description Formatting")
    class DescriptionFormatting {

        @Test
        @DisplayName("should format description for unique-named item")
        void shouldFormatDescriptionForUniqueItem() throws Throwable {
            // GIVEN
            CredentialProxy handler =
                    new CredentialProxy("UniqueName", "uuid-1", "UniqueName", StringCredentialsImpl.class);
            Method getDescription = StandardCredentials.class.getMethod("getDescription");

            // WHEN
            String description = (String) handler.invoke(null, getDescription, null);

            // THEN
            assertEquals("UniqueName (BW ID: uuid-1)", description);
        }

        @Test
        @DisplayName("should format description for duplicate-named item")
        void shouldFormatDescriptionForDuplicateItem() throws Throwable {
            // GIVEN
            CredentialProxy handler =
                    new CredentialProxy("uuid-1", "uuid-1", "DuplicateName", StringCredentialsImpl.class);
            Method getDescription = StandardCredentials.class.getMethod("getDescription");

            // WHEN
            String description = (String) handler.invoke(null, getDescription, null);

            // THEN
            assertEquals("DuplicateName (BW ID: uuid-1, non-unique name)", description);
        }

        @Test
        @DisplayName("should format description for file-type credential")
        void shouldFormatDescriptionForFileCredential() throws Throwable {
            // GIVEN
            CredentialProxy handler =
                    new CredentialProxy("my-file.env", "uuid-1", "my-file.env", StringCredentialsImpl.class);
            Method getDescription = StandardCredentials.class.getMethod("getDescription");

            // WHEN
            String description = (String) handler.invoke(null, getDescription, null);

            // THEN
            assertEquals("BW ID: uuid-1", description);
        }
    }
}
