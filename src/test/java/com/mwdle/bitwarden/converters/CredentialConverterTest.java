package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that {@link CredentialConverter#getConverter} routes each Bitwarden item type to the correct converter.
 */
@WithJenkins
@DisplayName("CredentialConverter.getConverter")
class CredentialConverterTest {

    private static BitwardenItemMetadata metadata(String name, BitwardenItemType type) {
        return new BitwardenItemMetadata("id", name, type);
    }

    @Test
    @DisplayName("routes LOGIN items to the login converter")
    void routesLogin(JenkinsRule ignored) {
        assertInstanceOf(
                LoginConverter.class, CredentialConverter.getConverter(metadata("login", BitwardenItemType.LOGIN)));
    }

    @Test
    @DisplayName("routes SSH_KEY items to the SSH key converter")
    void routesSshKey(JenkinsRule ignored) {
        assertInstanceOf(
                SshKeyConverter.class, CredentialConverter.getConverter(metadata("sshkey", BitwardenItemType.SSH_KEY)));
    }

    @Test
    @DisplayName("routes Secure Notes to the string converter by default")
    void routesSecureNoteToString(JenkinsRule ignored) {
        assertInstanceOf(
                SecureNoteStringConverter.class,
                CredentialConverter.getConverter(metadata("plain-note", BitwardenItemType.SECURE_NOTE)));
    }

    @Test
    @DisplayName("routes Secure Notes to the file converter when the name matches a configured suffix")
    void routesSecureNoteToFile(JenkinsRule ignored) {
        BitwardenConfig.getInstance().setFileCredentialSuffixes(".env");
        assertInstanceOf(
                SecureNoteFileConverter.class,
                CredentialConverter.getConverter(metadata("config.env", BitwardenItemType.SECURE_NOTE)));
    }

    @Test
    @DisplayName("returns null for unsupported item types")
    void returnsNullForUnsupportedTypes(JenkinsRule ignored) {
        assertNull(CredentialConverter.getConverter(metadata("card", BitwardenItemType.CARD)));
        assertNull(CredentialConverter.getConverter(metadata("identity", BitwardenItemType.IDENTITY)));
        assertNull(CredentialConverter.getConverter(metadata("unknown", BitwardenItemType.UNKNOWN)));
    }
}
