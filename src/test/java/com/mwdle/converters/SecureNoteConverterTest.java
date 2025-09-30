package com.mwdle.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.model.BitwardenItem;
import hudson.util.Secret;
import java.io.IOException;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the SecureNoteConverter class.
 */
@DisplayName("SecureNoteConverter")
class SecureNoteConverterTest {

    private SecureNoteConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SecureNoteConverter();
    }

    @Nested
    @DisplayName("canConvert() method")
    class CanConvert {

        @Test
        @DisplayName("should return true for an item with notes")
        void shouldReturnTrueForValidItem() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getNotes()).thenReturn(Secret.fromString("some secret content"));

            assertTrue(converter.canConvert(item), "Should be able to convert an item that has notes.");
        }

        @Test
        @DisplayName("should return false for an item without notes")
        void shouldReturnFalseForInvalidItem() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getNotes()).thenReturn(null);

            assertFalse(converter.canConvert(item), "Should not be able to convert an item with null notes.");
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {

        @Test
        @DisplayName("should convert a standard note to StringCredentials")
        void shouldConvertToStringCredentials() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getName()).thenReturn("My API Key");
            when(item.getNotes()).thenReturn(Secret.fromString("my-super-secret-value"));

            StringCredentials credential = (StringCredentials)
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test credential", item);

            assertNotNull(credential);
            assertInstanceOf(StringCredentials.class, credential, "Credential should be a StringCredentialsImpl.");
            assertEquals("cred-id", credential.getId());
            //            assertEquals("A test credential", credential.getDescription()); // TODO fix this, description
            // is no longer allowed to be set manually, it is configured by the item proxy class.
            assertEquals("my-super-secret-value", credential.getSecret().getPlainText());
        }

        @ParameterizedTest
        @ValueSource(strings = {"docker.env", "  production.env  ", "TEST.ENV"})
        @DisplayName("should convert a note ending in .env to FileCredentials")
        void shouldConvertToEnvFileCredentials(String envFileName) throws IOException {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getName()).thenReturn(envFileName);
            when(item.getNotes()).thenReturn(Secret.fromString("API_KEY=12345\nSECRET=abcde"));

            FileCredentials credential =
                    (FileCredentials) converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test .env file", item);

            assertNotNull(credential);
            assertInstanceOf(FileCredentials.class, credential, "Credential should be a FileCredentialsImpl.");
            assertEquals("cred-id", credential.getId());
            assertEquals("A test .env file", credential.getDescription());
            assertEquals(envFileName, credential.getFileName(), "File name should match the item name.");
            assertEquals(
                    "API_KEY=12345\nSECRET=abcde",
                    new String(credential.getContent().readAllBytes()));
        }
    }
}
