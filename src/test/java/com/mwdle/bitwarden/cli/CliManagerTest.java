package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.mwdle.bitwarden.BitwardenConfig;
import hudson.ProxyConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.util.SetSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

/**
 * Verifies that {@link CliManager} correctly honors user-provided paths and handles
 * download/provisioning contracts safely.
 */
@WithJenkins
@DisplayName("CliManager")
class CliManagerTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("getExecutablePath()")
    class GetExecutablePath {

        @Test
        @DisplayName("returns the user-provided manual CLI path without attempting to download")
        void usesUserProvidedPath(JenkinsRule ignored) throws Exception {
            File manualCli = tempDir.resolve("custom-bw").toFile();
            assertTrue(manualCli.createNewFile());
            assertTrue(manualCli.setExecutable(true));

            BitwardenConfig.getInstance().setCliExecutablePath(manualCli.getAbsolutePath());

            String path = CliManager.getExecutablePath();

            assertEquals(manualCli.getAbsolutePath(), path);
        }
    }

    @Nested
    @DisplayName("updateExecutable()")
    class UpdateExecutable {

        @Test
        @DisplayName("throws an IOException if the download returns a non-200 status code")
        @SuppressWarnings("unchecked")
        void throwsOnNon200Status(JenkinsRule ignored) throws Exception {
            HttpClient mockClient = mock(HttpClient.class);
            HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(404);

            try (MockedStatic<ProxyConfiguration> proxy = mockStatic(ProxyConfiguration.class)) {
                proxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);
                when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                        .thenReturn(mockResponse);

                assertThrows(IOException.class, CliManager::updateExecutable);
            }
        }

        @Test
        @DisplayName("throws an IOException for unsupported architectures")
        @SetSystemProperty(key = "os.arch", value = "arm64")
        @SetSystemProperty(key = "os.name", value = "Linux")
        void throwsOnUnsupportedArchitecture(JenkinsRule ignored) {
            assertThrows(IOException.class, CliManager::updateExecutable);
        }

        @Test
        @DisplayName("throws an IOException for unsupported operating systems")
        @SetSystemProperty(key = "os.arch", value = "amd64")
        @SetSystemProperty(key = "os.name", value = "Solaris")
        void throwsOnUnsupportedOs(JenkinsRule ignored) {
            assertThrows(IOException.class, CliManager::updateExecutable);
        }
    }
}
