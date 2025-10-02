package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenLogin;
import hudson.util.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the LoginConverter class.
 */
@DisplayName("LoginConverter")
class LoginConverterTest {

    private LoginConverter converter;

    @BeforeEach
    void setUp() {
        converter = new LoginConverter();
    }

    @Nested
    @DisplayName("canConvert() method")
    class CanConvert {

        @Test
        @DisplayName("should return true for a valid login item")
        void shouldReturnTrueForValidLoginItem() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getUsername()).thenReturn(Secret.fromString("user"));
            when(login.getPassword()).thenReturn(Secret.fromString("pass"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            assertTrue(converter.canConvert(item), "Should be able to convert a standard login item.");
        }

        @Test
        @DisplayName("should return true for a login item with only a username")
        void shouldReturnTrueForLoginItemWithOnlyUsername() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getUsername()).thenReturn(Secret.fromString("user"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            assertTrue(converter.canConvert(item), "Should be able to convert a login item with only a username.");
        }

        @Test
        @DisplayName("should return true for a login item with only a password")
        void shouldReturnTrueForLoginItemWithOnlyPassword() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getPassword()).thenReturn(Secret.fromString("pass"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            assertTrue(converter.canConvert(item), "Should be able to convert a login item with only a password.");
        }

        @Test
        @DisplayName("should return false for a non-login item")
        void shouldReturnFalseForNonLoginItem() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(null);

            assertFalse(converter.canConvert(item), "Should not be able to convert an item with no login data.");
        }

        @Test
        @DisplayName("should return false for a login item with no username or password")
        void shouldReturnFalseForEmptyLoginItem() {
            BitwardenLogin login = mock(BitwardenLogin.class);

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            assertFalse(converter.canConvert(item), "Should not convert a login item that has no credentials in it.");
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {

        @Test
        @DisplayName("should convert a valid item to StandardUsernamePasswordCredentials")
        void shouldConvertValidItem() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getUsername()).thenReturn(Secret.fromString("test-user"));
            when(login.getPassword()).thenReturn(Secret.fromString("test-pass"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            StandardUsernamePasswordCredentials credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test credential", item);

            assertNotNull(credential);
            assertEquals("cred-id", credential.getId());
            assertEquals("A test credential", credential.getDescription());
            assertEquals("test-user", credential.getUsername());
            assertEquals("test-pass", credential.getPassword().getPlainText());
        }

        @Test
        @DisplayName("should handle a null username gracefully")
        void shouldHandleNullUsername() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getPassword()).thenReturn(Secret.fromString("test-pass"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            StandardUsernamePasswordCredentials credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test credential", item);

            assertNotNull(credential);
            assertEquals("", credential.getUsername(), "A null username should be converted to an empty string.");
            assertEquals("test-pass", credential.getPassword().getPlainText());
        }

        @Test
        @DisplayName("should handle a null password gracefully")
        void shouldHandleNullPassword() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            when(login.getUsername()).thenReturn(Secret.fromString("test-user"));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getLogin()).thenReturn(login);

            StandardUsernamePasswordCredentials credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test credential", item);

            assertNotNull(credential);
            assertEquals("test-user", credential.getUsername());
            assertEquals(
                    "",
                    credential.getPassword().getPlainText(),
                    "A null password should be converted to an empty string.");
        }
    }
}
