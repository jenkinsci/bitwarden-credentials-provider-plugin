package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenLogin;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that {@link LoginConverter} turns Bitwarden Login items into Jenkins username/password credentials.
 */
@WithJenkins
@DisplayName("LoginConverter.convert")
class LoginConverterTest {

    private final LoginConverter converter = new LoginConverter();

    private static BitwardenItem loginItem(@CheckForNull String username, @CheckForNull String password) {
        BitwardenLogin login = new BitwardenLogin(
                username != null ? Secret.fromString(username) : null,
                password != null ? Secret.fromString(password) : null);
        return new BitwardenItem("id", "login", BitwardenItemType.LOGIN, null, login, null);
    }

    @Test
    @DisplayName("maps username and password onto the credential")
    void mapsUsernameAndPassword(JenkinsRule ignored) {
        StandardUsernamePasswordCredentials credential =
                converter.convert("cred-id", "a description", loginItem("admin", "hunter2"));

        assertEquals("cred-id", credential.getId());
        assertEquals("a description", credential.getDescription());
        assertEquals("admin", credential.getUsername());
        assertEquals("hunter2", credential.getPassword().getPlainText());
    }

    @Test
    @DisplayName("treats a missing username as empty")
    void missingUsernameBecomesEmpty(JenkinsRule ignored) {
        StandardUsernamePasswordCredentials credential =
                converter.convert("cred-id", "desc", loginItem(null, "hunter2"));

        assertEquals("", credential.getUsername());
        assertEquals("hunter2", credential.getPassword().getPlainText());
    }

    @Test
    @DisplayName("treats a missing password as empty")
    void missingPasswordBecomesEmpty(JenkinsRule ignored) {
        StandardUsernamePasswordCredentials credential = converter.convert("cred-id", "desc", loginItem("admin", null));

        assertEquals("admin", credential.getUsername());
        assertEquals("", credential.getPassword().getPlainText());
    }
}
