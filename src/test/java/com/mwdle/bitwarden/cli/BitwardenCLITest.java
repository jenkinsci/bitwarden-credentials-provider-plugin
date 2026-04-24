package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.PluginDirectoryProvider;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenStatus;
import hudson.util.Secret;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jenkins.security.ConfidentialStore;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the BitwardenCLI class.
 * <p>
 * This test suite verifies that the CLI wrapper correctly builds commands,
 * passes environment variables, and parses the output from the {@code bw}
 * executable. It mocks the {@link ProcessBuilder} and {@link Process} classes
 * to simulate various successful and failed CLI interactions.
 */
@DisplayName("BitwardenCLI")
class BitwardenCLITest {

    private static final String FAKE_EXECUTABLE_PATH = "/fake/path/bw";
    private static final String NO_INTERACTION_FLAG = "--nointeraction";
    private static final String RAW_FLAG = "--raw";
    // Mocks for static dependencies
    private static MockedStatic<BitwardenCLIManager> mockedCliManager;
    private static MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private static MockedStatic<ConfidentialStore> mockedConfidentialStore;
    private static MockedStatic<Secret> mockedSecret;
    private static MockedStatic<Messages> mockedMessages;

    @TempDir
    Path tempDir;
    // Mocks for constructed objects
    private MockedConstruction<ProcessBuilder> processBuilderMockedConstruction;
    // Mock instances
    @Mock
    private BitwardenCLIManager cliManagerMock;

    @Mock
    private ProcessBuilder processBuilderMock;

    @Mock
    private Process processMock;

    @Mock
    private ConfidentialStore confidentialStoreMock;

    @Mock
    private Map<String, String> environmentMapMock;

    @Mock
    private StandardUsernamePasswordCredentials apiKeyCredentialsMock;

    @Mock
    private StringCredentials masterPasswordCredentialsMock;

    private AutoCloseable closeable;
    private List<String> capturedCommand; // Field to store the command

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        closeable = MockitoAnnotations.openMocks(this);

        // Mock static utility classes
        mockedCliManager = mockStatic(BitwardenCLIManager.class);
        when(BitwardenCLIManager.getInstance()).thenReturn(cliManagerMock);
        when(cliManagerMock.getExecutablePath()).thenReturn(FAKE_EXECUTABLE_PATH);

        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        File fakePluginDataDir = tempDir.resolve("plugin-data").toFile();
        assertTrue(fakePluginDataDir.mkdirs(), "Failed to create fake plugin data dir");
        when(PluginDirectoryProvider.getPluginDataDirectory()).thenReturn(fakePluginDataDir);

        // Mock dependencies for Secret class
        mockedConfidentialStore = mockStatic(ConfidentialStore.class);
        when(ConfidentialStore.get()).thenReturn(confidentialStoreMock);
        mockedSecret = mockStatic(Secret.class);
        mockedSecret.when(() -> Secret.fromString(anyString())).thenAnswer(invocation -> {
            String plainText = invocation.getArgument(0);
            Secret secretMock = mock(Secret.class);
            when(secretMock.getPlainText()).thenReturn(plainText);
            return secretMock;
        });
        mockedSecret.when(() -> Secret.toString(any())).thenAnswer(invocation -> {
            Secret secret = invocation.getArgument(0);
            return (secret != null) ? secret.getPlainText() : null;
        });

        // Mock Messages for error validation
        mockedMessages = mockStatic(Messages.class);
        when(Messages.exception_connectionError()).thenReturn("Connection error");
        when(Messages.exception_loginError()).thenReturn("Login error");
        when(Messages.exception_unlockError()).thenReturn("Unlock error");
        when(Messages.exception_syncError()).thenReturn("Sync error");

        // Mock the construction of ProcessBuilder to intercept all CLI commands
        // This is the core of the test setup.
        processBuilderMockedConstruction = mockConstruction(ProcessBuilder.class, (mock, context) -> {
            // CAPTURE THE COMMAND LIST FROM THE CONSTRUCTOR
            capturedCommand = (List<String>) context.arguments().get(0);

            // When a ProcessBuilder is created, stub its methods
            when(mock.start()).thenReturn(processMock);
            when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
            // Return a mockable map for the environment
            when(mock.environment()).thenReturn(environmentMapMock);
            // Store the mock for later verification
            processBuilderMock = mock;
        });

        // Configure the default behavior for the mock Process
        // Tests can override this as needed
        when(processMock.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(processMock.waitFor()).thenReturn(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedCliManager.close();
        mockedPluginDir.close();
        mockedConfidentialStore.close();
        mockedSecret.close();
        mockedMessages.close();
        processBuilderMockedConstruction.close();
        closeable.close();
    }

    /**
     * Helper method to configure the mock process for a test.
     *
     * @param output   The string to be returned by the process's stdout.
     * @param exitCode The integer exit code to be returned by process.waitFor().
     */
    void setupMockProcess(String output, int exitCode) throws InterruptedException {
        InputStream inputStream = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        when(processMock.getInputStream()).thenReturn(inputStream);
        when(processMock.waitFor()).thenReturn(exitCode);
    }

    /**
     * Helper method to generate the expected command list, automatically prepending
     * the executable and global flags.
     */
    private List<String> expectedCommand(String... commandArgs) {
        List<String> expectedCommand = new ArrayList<>(List.of(FAKE_EXECUTABLE_PATH, NO_INTERACTION_FLAG, RAW_FLAG));
        expectedCommand.addAll(Arrays.asList(commandArgs));
        return expectedCommand;
    }

    /**
     * Helper method to verify the common side-effects of `executeCommand`.
     * This ensures our tests are as thorough as the production code.
     */
    void verifyExecuteCommandInternals() {
        // Verify the dedicated data directory is set for *every* command
        verify(environmentMapMock).put(eq("BITWARDENCLI_APPDATA_DIR"), endsWith(File.separator + "bwcli"));
        // Verify error stream is redirected for *every* command
        verify(processBuilderMock).redirectErrorStream(true);
    }

    @Nested
    @DisplayName("version()")
    class Version {
        @Test
        @DisplayName("should return version string on success")
        void shouldReturnVersionOnSuccess() throws IOException, InterruptedException {
            // GIVEN
            String expectedVersion = "2023.10.0";
            setupMockProcess(expectedVersion, 0);

            // WHEN
            String actualVersion = BitwardenCLI.version();

            // THEN
            assertEquals(expectedVersion, actualVersion);
            assertNotNull(capturedCommand, "Command list was not captured from constructor");
            assertEquals(expectedCommand("--version"), capturedCommand);
            verify(processBuilderMock).start();
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should trim whitespace from output")
        void shouldTrimWhitespaceFromOutput() throws IOException, InterruptedException {
            // GIVEN
            String expectedVersion = "2023.10.0";
            setupMockProcess(" \n" + expectedVersion + " \t \n", 0); // With whitespace

            // WHEN
            String actualVersion = BitwardenCLI.version();

            // THEN
            assertEquals(expectedVersion, actualVersion);
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw IOException on non-zero exit code")
        void shouldThrowIOExceptionOnFailure() throws InterruptedException {
            // GIVEN
            String errorOutput = "Command not found";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            IOException exception = assertThrows(IOException.class, BitwardenCLI::version);
            assertTrue(exception.getMessage().contains("Command failed with exit code 1"));
            assertTrue(exception.getMessage().contains(errorOutput));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("login()")
    class Login {

        @BeforeEach
        void setUpLogin() {
            // Use manual mock creation to avoid UnfinishedStubbingException
            Secret mockSecret = mock(Secret.class);
            when(mockSecret.getPlainText()).thenReturn("test-client-secret");

            when(apiKeyCredentialsMock.getUsername()).thenReturn("test-client-id");
            when(apiKeyCredentialsMock.getPassword()).thenReturn(mockSecret);
        }

        @Test
        @DisplayName("should set environment variables and succeed")
        void shouldSetEnvironmentVariables() throws IOException, InterruptedException {
            // GIVEN
            setupMockProcess("Login successful.", 0);

            // WHEN
            BitwardenCLI.login(apiKeyCredentialsMock);

            // THEN
            assertEquals(expectedCommand("login", "--apikey"), capturedCommand);
            verify(environmentMapMock).put("BW_CLIENTID", "test-client-id");
            verify(environmentMapMock).put("BW_CLIENTSECRET", "test-client-secret");
            verify(processBuilderMock).start();
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw BitwardenConnectionException on FetchError")
        void shouldThrowConnectionException() throws InterruptedException {
            // GIVEN
            String errorOutput = "FetchError: request to https://... failed";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            assertThrows(BitwardenConnectionException.class, () -> BitwardenCLI.login(apiKeyCredentialsMock));
            verifyExecuteCommandInternals();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Invalid API Key", "Username or password is incorrect", "Incorrect client_secret"})
        @DisplayName("should throw BitwardenAuthenticationException for all auth errors")
        void shouldThrowAuthenticationException(String errorOutput) throws InterruptedException {
            // GIVEN
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            assertThrows(BitwardenAuthenticationException.class, () -> BitwardenCLI.login(apiKeyCredentialsMock));
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw generic IOException for other errors")
        void shouldThrowGenericIOException() throws InterruptedException {
            // GIVEN
            String errorOutput = "An unexpected error occurred.";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            IOException exception = assertThrows(IOException.class, () -> BitwardenCLI.login(apiKeyCredentialsMock));

            // Verify it's not one of the specific subtypes
            assertFalse(exception instanceof BitwardenConnectionException);
            assertTrue(exception.getMessage().contains(errorOutput));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("logout()")
    class Logout {
        @Test
        @DisplayName("should call the logout command")
        void shouldCallLogoutCommand() throws IOException, InterruptedException {
            // GIVEN
            setupMockProcess("Logout successful.", 0);

            // WHEN
            BitwardenCLI.logout();

            // THEN
            assertEquals(expectedCommand("logout"), capturedCommand);
            verify(processBuilderMock).start();
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should ignore IOException on failure")
        void shouldIgnoreIOExceptionOnFailure() throws InterruptedException {
            // GIVEN
            setupMockProcess("You are not logged in.", 1);

            // WHEN & THEN
            // Verify that no exception is thrown, as the method is designed to ignore errors
            assertDoesNotThrow(BitwardenCLI::logout);
            assertEquals(expectedCommand("logout"), capturedCommand);
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("unlock()")
    class Unlock {
        @BeforeEach
        void setUpUnlock() {
            // Use manual mock creation to avoid UnfinishedStubbingException
            Secret mockSecret = mock(Secret.class);
            when(mockSecret.getPlainText()).thenReturn("test-master-password");
            when(masterPasswordCredentialsMock.getSecret()).thenReturn(mockSecret);
        }

        @Test
        @DisplayName("should set environment variable and return session token on success")
        void shouldSetEnvironmentVariableAndReturnToken() throws IOException, InterruptedException {
            // GIVEN
            String sessionToken = "some-session-token-12345";
            setupMockProcess(sessionToken, 0);

            // WHEN
            Secret resultToken = BitwardenCLI.unlock(masterPasswordCredentialsMock);

            // THEN
            assertEquals(expectedCommand("unlock", "--passwordenv", "BITWARDEN_MASTER_PASSWORD"), capturedCommand);
            verify(environmentMapMock).put("BITWARDEN_MASTER_PASSWORD", "test-master-password");
            verify(processBuilderMock).start();
            assertNotNull(resultToken);
            assertEquals(sessionToken, resultToken.getPlainText());
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw BitwardenConnectionException on FetchError")
        void shouldThrowConnectionException() throws InterruptedException {
            // GIVEN
            String errorOutput = "FetchError: request to https://... failed";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            assertThrows(BitwardenConnectionException.class, () -> BitwardenCLI.unlock(masterPasswordCredentialsMock));
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw BitwardenAuthenticationException for invalid master password")
        void shouldThrowAuthenticationException() throws InterruptedException {
            // GIVEN
            String errorOutput = "Invalid master password";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            assertThrows(
                    BitwardenAuthenticationException.class, () -> BitwardenCLI.unlock(masterPasswordCredentialsMock));
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw generic IOException for other errors")
        void shouldThrowGenericIOException() throws InterruptedException {
            // GIVEN
            String errorOutput = "An unexpected error occurred.";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            IOException exception =
                    assertThrows(IOException.class, () -> BitwardenCLI.unlock(masterPasswordCredentialsMock));

            // Verify it's not one of the specific subtypes
            assertFalse(exception instanceof BitwardenConnectionException);
            assertTrue(exception.getMessage().contains(errorOutput));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("sync()")
    class Sync {
        private Secret mockSessionToken;

        @BeforeEach
        void setUpSync() {
            mockSessionToken = mock(Secret.class);
            when(mockSessionToken.getPlainText()).thenReturn("test-session-token");
        }

        @Test
        @DisplayName("should set session token and succeed")
        void shouldSetSessionToken() throws IOException, InterruptedException {
            // GIVEN
            setupMockProcess("Sync complete.", 0);

            // WHEN
            BitwardenCLI.sync(mockSessionToken);

            // THEN
            assertEquals(expectedCommand("sync"), capturedCommand);
            verify(environmentMapMock).put("BW_SESSION", "test-session-token");
            verify(processBuilderMock).start();
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw BitwardenConnectionException on FetchError")
        void shouldThrowConnectionException() throws InterruptedException {
            // GIVEN
            String errorOutput = "FetchError: request to https://... failed";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            assertThrows(BitwardenConnectionException.class, () -> BitwardenCLI.sync(mockSessionToken));
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw generic IOException for other errors")
        void shouldThrowGenericIOException() throws InterruptedException {
            // GIVEN
            String errorOutput = "An unexpected error occurred.";
            setupMockProcess(errorOutput, 1);

            // WHEN & THEN
            IOException exception = assertThrows(IOException.class, () -> BitwardenCLI.sync(mockSessionToken));

            // Verify it's not one of the specific subtypes
            assertFalse(exception instanceof BitwardenConnectionException);
            assertTrue(exception.getMessage().contains(errorOutput));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("status()")
    class Status {
        private Secret mockSessionToken;

        @BeforeEach
        void setUpStatus() {
            mockSessionToken = mock(Secret.class);
            when(mockSessionToken.getPlainText()).thenReturn("test-session-token");
        }

        @Test
        @DisplayName("should return BitwardenStatus on success")
        void shouldReturnStatus() throws IOException, InterruptedException {
            // GIVEN
            String jsonOutput = "{\"serverUrl\": \"https://...\", \"status\": \"unlocked\"}";
            setupMockProcess(jsonOutput, 0);

            // WHEN
            BitwardenStatus status = BitwardenCLI.status(mockSessionToken);

            // THEN
            assertEquals(expectedCommand("status"), capturedCommand);
            verify(environmentMapMock).put("BW_SESSION", "test-session-token");
            verify(processBuilderMock).start();
            assertNotNull(status);
            assertEquals("unlocked", status.getStatus());
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw IOException on bad JSON")
        void shouldThrowIOExceptionOnBadJson() throws InterruptedException {
            // GIVEN
            String badJsonOutput = "this is not json";
            setupMockProcess(badJsonOutput, 0);

            // WHEN & THEN
            assertThrows(IOException.class, () -> BitwardenCLI.status(mockSessionToken));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("listItemsMetadata()")
    class ListItemsMetadata {
        private Secret mockSessionToken;

        @BeforeEach
        void setUpListItems() {
            mockSessionToken = mock(Secret.class);
            when(mockSessionToken.getPlainText()).thenReturn("test-session-token");
        }

        @Test
        @DisplayName("should return list of metadata on success")
        void shouldReturnListOfMetadata() throws IOException, InterruptedException {
            // GIVEN
            String jsonOutput = """
                    [
                        {"id": "uuid-1", "name": "Item 1", "type": 1},
                        {"id": "uuid-2", "name": "Item 2", "type": 2}
                    ]
                    """;
            setupMockProcess(jsonOutput, 0);

            // WHEN
            List<BitwardenItemMetadata> metadataList = BitwardenCLI.listItemsMetadata(mockSessionToken);

            // THEN
            assertEquals(expectedCommand("list", "items"), capturedCommand);
            verify(environmentMapMock).put("BW_SESSION", "test-session-token");
            verify(processBuilderMock).start();
            assertNotNull(metadataList);
            assertEquals(2, metadataList.size());
            assertEquals("uuid-1", metadataList.get(0).getId());
            assertEquals("Item 2", metadataList.get(1).getName());
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw IOException on bad JSON")
        void shouldThrowIOExceptionOnBadJson() throws InterruptedException {
            // GIVEN
            String badJsonOutput = "not a json array";
            setupMockProcess(badJsonOutput, 0);

            // WHEN & THEN
            assertThrows(IOException.class, () -> BitwardenCLI.listItemsMetadata(mockSessionToken));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("getItem()")
    class GetItem {
        private static final String ITEM_ID = "uuid-item-123";
        private Secret mockSessionToken;

        @BeforeEach
        void setUpGetItem() {
            mockSessionToken = mock(Secret.class);
            when(mockSessionToken.getPlainText()).thenReturn("test-session-token");
        }

        @Test
        @DisplayName("should return full item on success")
        void shouldReturnFullItem() throws IOException, InterruptedException {
            // GIVEN
            String jsonOutput = """
                    {
                        "id": "uuid-item-123",
                        "name": "My Login",
                        "login": {"username": "user", "password": "pass"}
                    }
                    """;
            setupMockProcess(jsonOutput, 0);

            // WHEN
            BitwardenItem item = BitwardenCLI.getItem(mockSessionToken, ITEM_ID);

            // THEN
            assertEquals(expectedCommand("get", "item", ITEM_ID), capturedCommand);
            verify(environmentMapMock).put("BW_SESSION", "test-session-token");
            verify(processBuilderMock).start();
            assertNotNull(item);
            assertEquals("My Login", item.getName());
            assertNotNull(item.getLogin());
            assertEquals("user", item.getLogin().getUsername().getPlainText());
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw IOException on bad JSON")
        void shouldThrowIOExceptionOnBadJson() throws InterruptedException {
            // GIVEN
            String badJsonOutput = "not a json object";
            setupMockProcess(badJsonOutput, 0);

            // WHEN & THEN
            assertThrows(IOException.class, () -> BitwardenCLI.getItem(mockSessionToken, ITEM_ID));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("configServer()")
    class ConfigServer {
        @Test
        @DisplayName("should call config command with server URL")
        void shouldCallConfigServer() throws IOException, InterruptedException {
            // GIVEN
            String serverUrl = "https://vault.example.com";
            setupMockProcess("Server configured.", 0);

            // WHEN
            BitwardenCLI.configServer(serverUrl);

            // THEN
            assertEquals(expectedCommand("config", "server", serverUrl), capturedCommand);
            verify(processBuilderMock).start();
            verifyExecuteCommandInternals();
        }

        @Test
        @DisplayName("should throw IOException on failure")
        void shouldThrowIOExceptionOnFailure() throws InterruptedException {
            // GIVEN
            String serverUrl = "https://vault.example.com";
            setupMockProcess("Invalid URL.", 1);

            // WHEN & THEN
            assertThrows(IOException.class, () -> BitwardenCLI.configServer(serverUrl));
            verifyExecuteCommandInternals();
        }
    }

    @Nested
    @DisplayName("clearBitwardenAppData()")
    class ClearBitwardenAppData {
        @Test
        @DisplayName("should delete data.json if it exists")
        void shouldDeleteDataJsonIfExists() throws IOException {
            // GIVEN: Manually create the file in our mocked plugin data directory
            File bwCliDir = new File(PluginDirectoryProvider.getPluginDataDirectory(), "bwcli");
            if (!bwCliDir.exists()) bwCliDir.mkdirs();

            File dataJson = new File(bwCliDir, "data.json");
            dataJson.createNewFile();
            assertTrue(dataJson.exists(), "Setup failed: data.json should exist before test");

            // WHEN
            BitwardenCLI.clearBitwardenAppData();

            // THEN
            assertFalse(dataJson.exists(), "data.json should have been deleted by clearBitwardenAppData");
        }

        @Test
        @DisplayName("should not throw error if data.json does not exist")
        void shouldNotThrowIfFileMissing() {
            // GIVEN: Ensure the file does NOT exist
            File bwCliDir = new File(PluginDirectoryProvider.getPluginDataDirectory(), "bwcli");
            File dataJson = new File(bwCliDir, "data.json");
            if (dataJson.exists()) dataJson.delete();

            // WHEN & THEN
            assertDoesNotThrow(
                    BitwardenCLI::clearBitwardenAppData,
                    "Method should handle missing files gracefully without throwing exceptions");
        }

        @Test
        @DisplayName("should log warning and handle IOException gracefully when deletion fails")
        void shouldHandleIOExceptionGracefully() {
            // GIVEN: We mock the static Files class to throw an error when deletion is attempted
            try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
                mockedFiles
                        .when(() -> Files.deleteIfExists(any(Path.class)))
                        .thenThrow(new IOException("Simulated filesystem error (e.g., file locked)"));

                // WHEN & THEN
                // We verify that the method catches the exception and does not throw it
                assertDoesNotThrow(
                        BitwardenCLI::clearBitwardenAppData,
                        "Method should catch IOException and log a warning instead of crashing");

                // Ensure the code actually attempted the deletion
                mockedFiles.verify(() -> Files.deleteIfExists(any(Path.class)), times(1));
            }
        }
    }
}
