package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import hudson.util.Secret;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialStore;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the SecureNoteConverter class.
 */
@DisplayName("SecureNoteConverter")
class SecureNoteConverterTest {

    @TempDir
    Path tempDir;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ConfidentialStore confidentialStoreMock;

    @Mock
    private BitwardenConfig configMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<ConfidentialStore> mockedConfidentialStore;
    private MockedStatic<Secret> mockedSecret;
    private MockedStatic<SecretBytes> mockedSecretBytes;
    private MockedStatic<BitwardenConfig> mockedConfig;

    private SecureNoteConverter converter;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getLegacyInstanceId()).thenReturn("test-instance-id");
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());

        mockedConfidentialStore = mockStatic(ConfidentialStore.class);
        when(ConfidentialStore.get()).thenReturn(confidentialStoreMock);

        mockedSecret = mockStatic(Secret.class);
        mockedSecret.when(() -> Secret.fromString(anyString())).thenAnswer(invocation -> {
            String plainText = invocation.getArgument(0);
            Secret secretMock = mock(Secret.class);
            when(secretMock.getPlainText()).thenReturn(plainText);
            return secretMock;
        });

        mockedSecretBytes = mockStatic(SecretBytes.class);
        mockedSecretBytes
                .when(() -> SecretBytes.fromRawBytes(any(byte[].class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = invocation.getArgument(0);
                    SecretBytes secretBytesMock = mock(SecretBytes.class);
                    when(secretBytesMock.getPlainData()).thenReturn(bytes);
                    return secretBytesMock;
                });

        mockedConfig = mockStatic(BitwardenConfig.class);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);

        converter = new SecureNoteConverter();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedConfidentialStore.close();
        mockedSecret.close();
        mockedSecretBytes.close();
        mockedConfig.close();
        closeable.close();
    }

    @Nested
    @DisplayName("canConvert methods")
    class CanConvert {
        @Test
        @DisplayName("should return true for SECURE_NOTE metadata type")
        void shouldReturnTrueForSecureNoteMetadata() {
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getItemType()).thenReturn(BitwardenItemType.SECURE_NOTE);
            assertTrue(converter.canConvert(metadata));
        }

        @Test
        @DisplayName("should return false for other metadata types")
        void shouldReturnFalseForOtherMetadataTypes() {
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getItemType()).thenReturn(BitwardenItemType.LOGIN);
            assertFalse(converter.canConvert(metadata));
        }

        @Test
        @DisplayName("should return true for an item with notes")
        void shouldReturnTrueForItemWithNotes() {
            BitwardenItem item = mock(BitwardenItem.class);
            Secret notesSecret = Secret.fromString("some content");
            when(item.getNotes()).thenReturn(notesSecret);
            assertTrue(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return false for an item without notes")
        void shouldReturnFalseForItemWithoutNotes() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getNotes()).thenReturn(null);
            assertFalse(converter.canConvert(item));
        }
    }

    @Nested
    @DisplayName("createProxy() method")
    class CreateProxy {
        @Test
        @DisplayName("should create a StringCredentials proxy for a standard note")
        void shouldCreateStringProxy() {
            // GIVEN
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env");
            when(jenkinsMock.getDescriptor(StringCredentialsImpl.class))
                    .thenReturn(new StringCredentialsImpl.DescriptorImpl());
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getName()).thenReturn("my-api-key");

            // WHEN
            StandardCredentials proxy = converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata);

            // THEN
            assertNotNull(proxy);
            assertInstanceOf(StringCredentials.class, proxy);
            assertInstanceOf(CredentialProxy.class, Proxy.getInvocationHandler(proxy));
        }

        @Test
        @DisplayName("should create a FileCredentials proxy for a matching suffix")
        void shouldCreateFileProxy() {
            // GIVEN
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env, .properties");
            when(jenkinsMock.getDescriptor(FileCredentialsImpl.class))
                    .thenReturn(new FileCredentialsImpl.DescriptorImpl());
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getName()).thenReturn("config.properties");

            // WHEN
            StandardCredentials proxy = converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata);

            // THEN
            assertNotNull(proxy);
            assertInstanceOf(FileCredentials.class, proxy);
            assertInstanceOf(CredentialProxy.class, Proxy.getInvocationHandler(proxy));
        }

        @Test
        @DisplayName("should return null if String descriptor is not found")
        void shouldReturnNullForMissingStringDescriptor() {
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env");
            when(jenkinsMock.getDescriptor(StringCredentialsImpl.class)).thenReturn(null);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getName()).thenReturn("my-api-key");

            assertNull(converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata));
        }

        @Test
        @DisplayName("should return null if File descriptor is not found")
        void shouldReturnNullForMissingFileDescriptor() {
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env");
            when(jenkinsMock.getDescriptor(FileCredentialsImpl.class)).thenReturn(null);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getName()).thenReturn("my-file.env");

            assertNull(converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata));
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {

        @Test
        @DisplayName("should convert a standard note to StringCredentials")
        void shouldConvertToStringCredentials() {
            // GIVEN
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env");
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getName()).thenReturn("My API Key");
            Secret notesSecret = Secret.fromString("my-super-secret-value");
            when(item.getNotes()).thenReturn(notesSecret);

            // WHEN
            StringCredentials credential = (StringCredentials)
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test credential", item);

            // THEN
            assertNotNull(credential);
            assertInstanceOf(StringCredentialsImpl.class, credential);
            assertEquals("cred-id", credential.getId());
            assertEquals("my-super-secret-value", credential.getSecret().getPlainText());
        }

        @ParameterizedTest
        @ValueSource(strings = {"docker.env", "  production.env  ", "TEST.PROPERTIES"})
        @DisplayName("should convert a note with matching suffix to FileCredentials")
        void shouldConvertToEnvFileCredentials(String envFileName) {
            // GIVEN
            when(configMock.getFileCredentialSuffixes()).thenReturn(".env, .properties");
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getName()).thenReturn(envFileName);
            Secret notesSecret = Secret.fromString("API_KEY=12345");
            when(item.getNotes()).thenReturn(notesSecret);

            // WHEN
            FileCredentials credential =
                    (FileCredentials) converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test .env file", item);

            // THEN
            assertNotNull(credential);
            assertInstanceOf(FileCredentialsImpl.class, credential);
            assertEquals("cred-id", credential.getId());
            // Verify that the file name is NOT trimmed, which is the actual behavior.
            assertEquals(envFileName, credential.getFileName());

            // Verify the raw bytes were passed correctly
            ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
            mockedSecretBytes.verify(() -> SecretBytes.fromRawBytes(captor.capture()));
            assertEquals("API_KEY=12345", new String(captor.getValue(), StandardCharsets.UTF_8));
        }
    }
}
