package com.mwdle.bitwarden.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that the JSON emitted by the Bitwarden CLI deserializes into the plugin's model classes, including the
 * custom mapping of sensitive fields into Jenkins {@link hudson.util.Secret} instances.
 */
@DisplayName("Model deserialization")
class ModelDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    @DisplayName("BitwardenItem")
    class Items {

        @Test
        @DisplayName("deserializes a Login item with username, password, and notes as Secrets")
        void deserializesLogin() throws Exception {
            String json = """
                    {
                      "id": "a1b2c3d4",
                      "name": "login",
                      "type": 1,
                      "notes": "a note",
                      "login": {"username": "admin", "password": "hunter2"}
                    }
                    """;

            BitwardenItem item = mapper.readValue(json, BitwardenItem.class);

            assertEquals("a1b2c3d4", item.id);
            assertEquals("login", item.name);
            assertEquals(BitwardenItemType.LOGIN, item.type);
            assertNotNull(item.notes);
            assertEquals("a note", item.notes.getPlainText());
            assertNotNull(item.login);
            assertNotNull(item.login.username());
            assertEquals("admin", item.login.username().getPlainText());
            assertNotNull(item.login.password());
            assertEquals("hunter2", item.login.password().getPlainText());
            assertNull(item.sshKey);
        }

        @Test
        @DisplayName("deserializes an SSH Key item with a Secret private key and plaintext public key")
        void deserializesSshKey() throws Exception {
            String json = """
                    {
                      "id": "f0e9d8c7",
                      "name": "sshkey",
                      "type": 5,
                      "sshKey": {"privateKey": "PRIVATE", "publicKey": "ssh-ed25519 AAAA user@host"}
                    }
                    """;

            BitwardenItem item = mapper.readValue(json, BitwardenItem.class);

            assertEquals(BitwardenItemType.SSH_KEY, item.type);
            assertNotNull(item.sshKey);
            assertNotNull(item.sshKey.privateKey());
            assertEquals("PRIVATE", item.sshKey.privateKey().getPlainText());
            assertEquals("ssh-ed25519 AAAA user@host", item.sshKey.publicKey());
            assertNull(item.login);
            assertNull(item.notes);
        }

        @Test
        @DisplayName("deserializes a Secure Note item")
        void deserializesSecureNote() throws Exception {
            String json = """
                    {"id": "11223344", "name": "securenote", "type": 2, "notes": "secret content"}
                    """;

            BitwardenItem item = mapper.readValue(json, BitwardenItem.class);

            assertEquals(BitwardenItemType.SECURE_NOTE, item.type);
            assertNotNull(item.notes);
            assertEquals("secret content", item.notes.getPlainText());
            assertNull(item.login);
            assertNull(item.sshKey);
        }

        @Test
        @DisplayName("leaves absent optional fields null")
        void deserializesWithoutOptionalFields() throws Exception {
            String json = """
                    {"id": "id", "name": "name", "type": 1}
                    """;

            BitwardenItem item = mapper.readValue(json, BitwardenItem.class);

            assertNull(item.notes);
            assertNull(item.login);
            assertNull(item.sshKey);
        }

        @Test
        @DisplayName("ignores unknown fields so future CLI additions do not break parsing")
        void ignoresUnknownFields() {
            String json = """
                    {"id": "id", "name": "name", "type": 1, "brandNewField": "x", "nested": {"a": 1}}
                    """;

            assertDoesNotThrow(() -> {
                BitwardenItem item = mapper.readValue(json, BitwardenItem.class);
                assertEquals("id", item.id);
            });
        }
    }

    @Nested
    @DisplayName("BitwardenItemMetadata")
    class Metadata {

        @Test
        @DisplayName("deserializes the lightweight metadata fields")
        void deserializesMetadata() throws Exception {
            String json = """
                    {"id": "uuid-123", "name": "My Item", "type": 2}
                    """;

            BitwardenItemMetadata metadata = mapper.readValue(json, BitwardenItemMetadata.class);

            assertEquals("uuid-123", metadata.id);
            assertEquals("My Item", metadata.name);
            assertEquals(BitwardenItemType.SECURE_NOTE, metadata.type);
        }
    }

    @Nested
    @DisplayName("BitwardenItemType")
    class Types {

        @ParameterizedTest
        @CsvSource({"1, LOGIN", "2, SECURE_NOTE", "3, CARD", "4, IDENTITY", "5, SSH_KEY"})
        @DisplayName("maps known CLI type codes to enum constants")
        void mapsKnownCodes(int code, BitwardenItemType expected) {
            assertEquals(expected, BitwardenItemType.fromInteger(code));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 6, 99, -1})
        @DisplayName("maps unrecognized codes to UNKNOWN")
        void mapsUnknownCodes(int code) {
            assertEquals(BitwardenItemType.UNKNOWN, BitwardenItemType.fromInteger(code));
        }
    }
}
