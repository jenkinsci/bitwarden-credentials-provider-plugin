package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemType;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that Secure Notes convert into either String or File credentials.
 */
@WithJenkins
@DisplayName("Secure Note converters")
class SecureNoteConverterTest {

    private static BitwardenItem note(String name, @CheckForNull String notes) {
        return new BitwardenItem(
                "id", name, BitwardenItemType.SECURE_NOTE, notes != null ? Secret.fromString(notes) : null, null, null);
    }

    @Nested
    @DisplayName("SecureNoteStringConverter.convert")
    class StringConverter {

        private final SecureNoteStringConverter converter = new SecureNoteStringConverter();

        @Test
        @DisplayName("maps the note body onto the secret text")
        void mapsNoteBody(JenkinsRule ignored) {
            StringCredentialsImpl credential = converter.convert("cred-id", "desc", note("My Note", "secret content"));

            assertEquals("cred-id", credential.getId());
            assertEquals("desc", credential.getDescription());
            assertEquals("secret content", credential.getSecret().getPlainText());
        }

        @Test
        @DisplayName("treats a missing note body as empty")
        void missingNoteBecomesEmpty(JenkinsRule ignored) {
            StringCredentialsImpl credential = converter.convert("cred-id", "desc", note("My Note", null));

            assertEquals("", credential.getSecret().getPlainText());
        }
    }

    @Nested
    @DisplayName("SecureNoteFileConverter.convert")
    class FileConverter {

        private final SecureNoteFileConverter converter = new SecureNoteFileConverter();

        @Test
        @DisplayName("maps the note body onto the file content and uses the item name as the file name")
        void mapsNoteBodyToFile(JenkinsRule ignored) throws Exception {
            FileCredentialsImpl credential = converter.convert("cred-id", "desc", note("config.env", "API_KEY=123"));

            assertEquals("cred-id", credential.getId());
            assertEquals("config.env", credential.getFileName());
            try (InputStream content = credential.getContent()) {
                assertArrayEquals("API_KEY=123".getBytes(StandardCharsets.UTF_8), content.readAllBytes());
            }
        }

        @Test
        @DisplayName("treats a missing note body as an empty file")
        void missingNoteBecomesEmptyFile(JenkinsRule ignored) throws Exception {
            FileCredentialsImpl credential = converter.convert("cred-id", "desc", note("config.env", null));

            try (InputStream content = credential.getContent()) {
                assertArrayEquals(new byte[0], content.readAllBytes());
            }
        }
    }
}
