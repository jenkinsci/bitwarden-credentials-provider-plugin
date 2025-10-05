package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenSshKey;
import hudson.model.Descriptor;
import hudson.util.Secret;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the SshKeyConverter class.
 */
@DisplayName("SshKeyConverter")
class SshKeyConverterTest {

    @TempDir
    Path tempDir;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ConfidentialStore confidentialStoreMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<ConfidentialStore> mockedConfidentialStore;
    private MockedStatic<Secret> mockedSecret;

    private SshKeyConverter converter;
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

        converter = new SshKeyConverter();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedConfidentialStore.close();
        mockedSecret.close();
        closeable.close();
    }

    @Nested
    @DisplayName("canConvert methods")
    class CanConvert {
        @Test
        @DisplayName("should return true for SSH_KEY metadata type")
        void shouldReturnTrueForSshKeyMetadata() {
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getItemType()).thenReturn(BitwardenItemType.SSH_KEY);
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
        @DisplayName("should return true for an item with a private key")
        void shouldReturnTrueForItemWithPrivateKey() {
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            Secret privateKeySecret = Secret.fromString("-----BEGIN...");
            when(sshKey.getPrivateKey()).thenReturn(privateKeySecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);
            assertTrue(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return false for an item without SSH key data")
        void shouldReturnFalseForItemWithoutSshKey() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(null);
            assertFalse(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return false for an SSH key item without a private key")
        void shouldReturnFalseForSshKeyItemWithoutPrivateKey() {
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(null);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);
            assertFalse(converter.canConvert(item));
        }
    }

    @Nested
    @DisplayName("createProxy() method")
    class CreateProxy {
        @Test
        @DisplayName("should create a valid proxy when descriptor is found")
        void shouldCreateProxySuccessfully() {
            // GIVEN
            Descriptor<?> testDescriptor = new BasicSSHUserPrivateKey.DescriptorImpl();
            when(jenkinsMock.getDescriptor(BasicSSHUserPrivateKey.class)).thenReturn(testDescriptor);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.getId()).thenReturn("item-id");
            when(metadata.getName()).thenReturn("Item Name");

            // WHEN
            SSHUserPrivateKey proxy = converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata);

            // THEN
            assertNotNull(proxy);
            assertInstanceOf(CredentialProxy.class, Proxy.getInvocationHandler(proxy));
        }

        @Test
        @DisplayName("should return null when descriptor is not found")
        void shouldReturnNullWhenDescriptorIsMissing() {
            // GIVEN
            when(jenkinsMock.getDescriptor(BasicSSHUserPrivateKey.class)).thenReturn(null);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);

            // WHEN
            SSHUserPrivateKey proxy = converter.createProxy(CredentialsScope.GLOBAL, "cred-id", metadata);

            // THEN
            assertNull(proxy);
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {
        @Test
        @DisplayName("should convert an SSH key and derive username from public key comment")
        void shouldConvertAndDeriveUsername() {
            // GIVEN
            String privateKeyContent = "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----\n";
            String publicKeyContent = "ssh-rsa AAAAB3... jenkins@my-server";
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            Secret privateKeySecret = Secret.fromString(privateKeyContent);
            when(sshKey.getPrivateKey()).thenReturn(privateKeySecret);
            when(sshKey.getPublicKey()).thenReturn(publicKeyContent);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            // Mock the construction of BasicSSHUserPrivateKey to verify constructor args
            try (MockedConstruction<BasicSSHUserPrivateKey> mockedConstruction =
                    mockConstruction(BasicSSHUserPrivateKey.class, (mock, context) -> {
                        // Assert that the constructor was called with the correct username
                        assertEquals("jenkins", context.arguments().get(2));
                        // Assert that the private key source contains the correct key
                        BasicSSHUserPrivateKey.DirectEntryPrivateKeySource source =
                                (BasicSSHUserPrivateKey.DirectEntryPrivateKeySource)
                                        context.arguments().get(3);
                        assertEquals(privateKeyContent, source.getPrivateKey().getPlainText());
                    })) {
                // WHEN
                converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test SSH key", item);

                // THEN
                assertEquals(1, mockedConstruction.constructed().size());
            }
        }

        @Test
        @DisplayName("should have an empty username if public key has no comment")
        void shouldHandleNoUsernameComment() {
            // GIVEN
            String privateKeyContent = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----\n";
            String publicKeyContent = "ssh-ed25519 AAAAC3..."; // No comment
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            Secret privateKeySecret = Secret.fromString(privateKeyContent);
            when(sshKey.getPrivateKey()).thenReturn(privateKeySecret);
            when(sshKey.getPublicKey()).thenReturn(publicKeyContent);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            try (MockedConstruction<BasicSSHUserPrivateKey> mockedConstruction = mockConstruction(
                    BasicSSHUserPrivateKey.class,
                    (mock, context) -> assertEquals("", context.arguments().get(2)))) {
                // WHEN
                converter.convert(CredentialsScope.GLOBAL, "cred-id", "Key without comment", item);

                // THEN
                assertEquals(1, mockedConstruction.constructed().size());
            }
        }

        @Test
        @DisplayName("should have an empty username if public key is null")
        void shouldHandleNullPublicKey() {
            // GIVEN
            String privateKeyContent = "-----BEGIN EC PRIVATE KEY-----\n...\n-----END EC PRIVATE KEY-----\n";
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            Secret privateKeySecret = Secret.fromString(privateKeyContent);
            when(sshKey.getPrivateKey()).thenReturn(privateKeySecret);
            when(sshKey.getPublicKey()).thenReturn(null); // Public key is null
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            try (MockedConstruction<BasicSSHUserPrivateKey> mockedConstruction = mockConstruction(
                    BasicSSHUserPrivateKey.class,
                    (mock, context) -> assertEquals("", context.arguments().get(2)))) {
                // WHEN
                converter.convert(CredentialsScope.GLOBAL, "cred-id", "Key with null public key", item);

                // THEN
                assertEquals(1, mockedConstruction.constructed().size());
            }
        }
    }
}
