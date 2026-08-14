package com.mwdle.bitwarden.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import hudson.util.Secret;
import java.io.IOException;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the data model classes.
 * Verifies that JSON from the Bitwarden CLI is correctly deserialized into the corresponding objects,
 * including the custom deserialization of sensitive fields into Jenkins Secrets.
 */
@DisplayName("Model Deserialization")
class ModelDeserializationTest {

    private ObjectMapper objectMapper;
    private MockedStatic<Secret> mockedSecret;
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<ConfidentialStore> mockedConfidentialStore;
    private AutoCloseable closeable;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ConfidentialStore confidentialStoreMock;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();

        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);

        mockedConfidentialStore = mockStatic(ConfidentialStore.class);
        when(ConfidentialStore.get()).thenReturn(confidentialStoreMock);

        mockedSecret = mockStatic(Secret.class);
        mockedSecret.when(() -> Secret.fromString(anyString())).thenAnswer(invocation -> {
            String plainText = invocation.getArgument(0);
            Secret secretMock = mock(Secret.class);
            when(secretMock.getPlainText()).thenReturn(plainText);
            return secretMock;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedSecret.close();
        mockedJenkins.close();
        mockedConfidentialStore.close();
        closeable.close();
    }

    @Nested
    @DisplayName("BitwardenItem Deserialization")
    class BitwardenItemTests {

        @Test
        @DisplayName("should correctly deserialize a standard Login item")
        void shouldDeserializeLoginItem() throws Exception {
            String loginJson = """
                    {
                        "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
                        "type": 1,
                        "name": "My Jenkins API Key",
                        "notes": "This is a secret note.",
                        "login": {
                            "username": "admin-user",
                            "password": "super-secret-password"
                        },
                        "sshKey": null
                    }
                    """;

            BitwardenItem item = objectMapper.readValue(loginJson, BitwardenItem.class);

            assertNotNull(item);
            assertEquals("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", item.id);
            assertEquals(BitwardenItemType.LOGIN, item.type);
            assertEquals("My Jenkins API Key", item.name);
            assertNotNull(item.notes);
            assertEquals("This is a secret note.", item.notes.getPlainText());
            assertNotNull(item.login);
            assertNotNull(item.login.username());
            assertEquals("admin-user", item.login.username().getPlainText());
            assertNotNull(item.login.password());
            assertEquals("super-secret-password", item.login.password().getPlainText());
            assertNull(item.sshKey);
        }

        @Test
        @DisplayName("should correctly deserialize an SSH Key item")
        void shouldDeserializeSshKeyItem() throws Exception {
            String sshKeyJson = """
                    {
                        "id": "f0e9d8c7-b6a5-4f3e-2d1c-0b9a8f7e6d5c",
                        "type": 5,
                        "name": "GitHub Deploy Key",
                        "notes": null,
                        "login": null,
                        "sshKey": {
                            "privateKey": "-----BEGIN RSA PRIVATE KEY-----\\nSUPER_DUPER_SECRET_PRIVATE_KEY\\n-----END RSA PRIVATE KEY-----",
                            "publicKey": "ssh-rsa AAAAB3NzaC1yc2EAAA..."
                        }
                    }
                    """;

            BitwardenItem item = objectMapper.readValue(sshKeyJson, BitwardenItem.class);

            assertNotNull(item);
            assertEquals("f0e9d8c7-b6a5-4f3e-2d1c-0b9a8f7e6d5c", item.id);
            assertEquals(BitwardenItemType.SSH_KEY, item.type);
            assertEquals("GitHub Deploy Key", item.name);
            assertNotNull(item.sshKey);
            assertNotNull(item.sshKey.privateKey());
            assertEquals(
                    "-----BEGIN RSA PRIVATE KEY-----\nSUPER_DUPER_SECRET_PRIVATE_KEY\n-----END RSA PRIVATE KEY-----",
                    item.sshKey.privateKey().getPlainText());
            assertEquals("ssh-rsa AAAAB3NzaC1yc2EAAA...", item.sshKey.publicKey());
            assertNull(item.notes);
            assertNull(item.login);
        }

        @Test
        @DisplayName("should correctly deserialize a Secure Note item")
        void shouldDeserializeSecureNoteItem() throws Exception {
            String secureNoteJson = """
                    {
                        "id": "11223344-5566-7788-9900-aabbccddeeff",
                        "type": 2,
                        "name": "My Secure Note",
                        "notes": "Content of the secure note.",
                        "login": null,
                        "sshKey": null
                    }
                    """;

            BitwardenItem item = objectMapper.readValue(secureNoteJson, BitwardenItem.class);

            assertNotNull(item);
            assertEquals("11223344-5566-7788-9900-aabbccddeeff", item.id);
            assertEquals(BitwardenItemType.SECURE_NOTE, item.type);
            assertEquals("My Secure Note", item.name);
            assertNotNull(item.notes);
            assertEquals("Content of the secure note.", item.notes.getPlainText());
            assertNull(item.login);
            assertNull(item.sshKey);
        }

        @Test
        @DisplayName("should ignore unknown fields to allow for future CLI updates")
        void shouldHandleUnknownFieldsGracefully() {
            String futureJson = """
                    {
                        "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
                        "name": "My Jenkins API Key",
                        "aNewFieldBitwardenAdded": "some new value",
                        "anotherFutureProperty": { "nested": true }
                    }
                    """;

            assertDoesNotThrow(() -> {
                BitwardenItem item = objectMapper.readValue(futureJson, BitwardenItem.class);
                assertEquals("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", item.id);
                assertEquals("My Jenkins API Key", item.name);
            });
        }
    }

    @Nested
    @DisplayName("BitwardenItemMetadata Deserialization")
    class BitwardenItemMetadataTests {
        @Test
        @DisplayName("should correctly deserialize metadata")
        void shouldDeserializeMetadata() throws JsonProcessingException {
            String metadataJson = """
                    {
                        "id": "uuid-123",
                        "name": "My Item",
                        "type": 1
                    }
                    """;
            BitwardenItemMetadata metadata = objectMapper.readValue(metadataJson, BitwardenItemMetadata.class);
            assertEquals("uuid-123", metadata.id);
            assertEquals("My Item", metadata.name);
            assertEquals(BitwardenItemType.LOGIN, metadata.type);
        }
    }

    @Nested
    @DisplayName("BitwardenItemType Deserialization")
    class BitwardenItemTypeTests {
        @ParameterizedTest
        @CsvSource({"1, LOGIN", "2, SECURE_NOTE", "3, CARD", "4, IDENTITY", "5, SSH_KEY"})
        @DisplayName("should correctly map known type codes")
        void shouldMapKnownTypeCodes(int code, BitwardenItemType expectedType) {
            assertEquals(expectedType, BitwardenItemType.fromInteger(code));
        }

        @Test
        @DisplayName("should map unknown type code to UNKNOWN")
        void shouldMapUnknownCodeToUnknown() {
            assertEquals(BitwardenItemType.UNKNOWN, BitwardenItemType.fromInteger(99));
        }
    }

    @Nested
    @DisplayName("SecretDeserializer")
    class SecretDeserializerTests {
        // A simple wrapper class to test the deserializer in isolation
        private static class SecretWrapper {
            @JsonDeserialize(using = SecretDeserializer.class)
            public Secret secretField;
        }

        @Test
        @DisplayName("should deserialize a string into a Secret object")
        void shouldDeserializeStringToSecret() throws IOException {
            String json = "{\"secretField\": \"my-secret-value\"}";
            SecretWrapper wrapper = objectMapper.readValue(json, SecretWrapper.class);
            assertNotNull(wrapper.secretField);
            assertEquals("my-secret-value", wrapper.secretField.getPlainText());
        }

        @Test
        @DisplayName("should deserialize a null value to a null Secret")
        void shouldDeserializeNullToNull() throws IOException {
            String json = "{\"secretField\": null}";
            SecretWrapper wrapper = objectMapper.readValue(json, SecretWrapper.class);
            assertNull(wrapper.secretField);
        }
    }
}
