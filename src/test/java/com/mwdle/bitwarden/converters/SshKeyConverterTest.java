package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenSshKey;
import hudson.util.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SshKeyConverter class.
 */
@DisplayName("SshKeyConverter")
class SshKeyConverterTest {

    private SshKeyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SshKeyConverter();
    }

    @Nested
    @DisplayName("canConvert() method")
    class CanConvert {

        @Test
        @DisplayName("should return true for a valid SSH key item")
        void shouldReturnTrueForValidItem() {
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(Secret.fromString("-----BEGIN..."));

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            assertTrue(converter.canConvert(item), "Should be able to convert an item with a private key.");
        }

        @Test
        @DisplayName("should return false for an item that is not an SSH key")
        void shouldReturnFalseForNonSshKeyItem() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(null);

            assertFalse(converter.canConvert(item), "Should not convert an item with no SSH key data.");
        }

        @Test
        @DisplayName("should return false for an SSH key item with no private key")
        void shouldReturnFalseForSshKeyItemWithNoPrivateKey() {
            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(null); // The private key is missing

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            assertFalse(
                    converter.canConvert(item), "Should not convert an SSH key item that is missing the private key.");
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {

        @Test
        @DisplayName("should convert an SSH key and derive username from public key comment")
        void shouldConvertAndDeriveUsername() {
            // End the mock private key with a newline to ensure it matches the convention from the
            // BasicSSHUserPrivateKey class.
            String privateKeyContent = "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----\n";
            String publicKeyContent = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC... jenkins@my-server";

            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(Secret.fromString(privateKeyContent));
            when(sshKey.getPublicKey()).thenReturn(publicKeyContent);

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            BasicSSHUserPrivateKey credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "A test SSH key", item);

            assertNotNull(credential);
            assertInstanceOf(BasicSSHUserPrivateKey.class, credential);
            assertEquals("cred-id", credential.getId());
            assertEquals("A test SSH key", credential.getDescription());
            assertEquals(
                    "jenkins", credential.getUsername(), "Username should be derived from the public key comment.");
            assertEquals(privateKeyContent, credential.getPrivateKeys().get(0));
        }

        @Test
        @DisplayName("should convert an SSH key with an empty username if no comment exists")
        void shouldHandleNoUsernameComment() {
            // End the mock private key with a newline to ensure it matches the convention from the
            // BasicSSHUserPrivateKey class.
            String privateKeyContent = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----\n";
            String publicKeyContent = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA..."; // No comment

            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(Secret.fromString(privateKeyContent));
            when(sshKey.getPublicKey()).thenReturn(publicKeyContent);

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            BasicSSHUserPrivateKey credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "Key without comment", item);

            // Assert against the expected fallback behavior of the credential class
            assertEquals(
                    System.getProperty("user.name"),
                    credential.getUsername(),
                    "An empty username should cause the credential to fall back to the system username.");
            assertEquals(privateKeyContent, credential.getPrivateKeys().get(0));
        }

        @Test
        @DisplayName("should convert an SSH key with an empty username if public key is null")
        void shouldHandleNullPublicKey() {
            // End the mock private key with a newline to ensure it matches the convention from the
            // BasicSSHUserPrivateKey class.
            String privateKeyContent = "-----BEGIN EC PRIVATE KEY-----\n...\n-----END EC PRIVATE KEY-----\n";

            BitwardenSshKey sshKey = mock(BitwardenSshKey.class);
            when(sshKey.getPrivateKey()).thenReturn(Secret.fromString(privateKeyContent));
            when(sshKey.getPublicKey()).thenReturn(null); // Public key is null

            BitwardenItem item = mock(BitwardenItem.class);
            when(item.getSshKey()).thenReturn(sshKey);

            BasicSSHUserPrivateKey credential =
                    converter.convert(CredentialsScope.GLOBAL, "cred-id", "Key with null public key", item);

            // FIXED: Assert against the expected fallback behavior of the credential class
            assertEquals(
                    System.getProperty("user.name"),
                    credential.getUsername(),
                    "An empty username should cause the credential to fall back to the system username.");
            assertEquals(privateKeyContent, credential.getPrivateKeys().get(0));
        }
    }
}
