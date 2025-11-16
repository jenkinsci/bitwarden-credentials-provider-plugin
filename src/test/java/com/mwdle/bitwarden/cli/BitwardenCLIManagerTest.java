package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.PluginDirectoryProvider;
import hudson.ProxyConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the BitwardenCLIManager class.
 * <p>
 * This test suite uses a spy to allow partial mocking, enabling us to test the
 * class's logic while stubbing out methods that perform real I/O. It controls
 * the environment by setting the `os.name` system property, ensuring
 * deterministic behavior for OS-dependent code.
 */
@DisplayName("BitwardenCLIManager")
class BitwardenCLIManagerTest {
    @TempDir
    Path tempDir;

    private MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private MockedStatic<ProxyConfiguration> mockedProxy;
    private MockedStatic<BitwardenConfig> mockedConfig;

    @Mock
    private BitwardenConfig configMock;

    private BitwardenCLIManager manager;
    private String originalOsName;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        originalOsName = System.getProperty("os.name");
        closeable = MockitoAnnotations.openMocks(this);

        Constructor<BitwardenCLIManager> constructor = BitwardenCLIManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BitwardenCLIManager instance = constructor.newInstance();
        manager = spy(instance);

        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        mockedPluginDir.when(PluginDirectoryProvider::getPluginDataDirectory).thenReturn(tempDir.toFile());

        mockedProxy = mockStatic(ProxyConfiguration.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedPluginDir.close();
        mockedProxy.close();
        mockedConfig.close();
        closeable.close();
        System.setProperty("os.name", originalOsName);
    }

    @Nested
    @DisplayName("getExecutablePath() method")
    class GetExecutablePath {

        @Test
        @DisplayName("should use user-provided path when it is valid")
        void shouldUseUserProvidedPathWhenValid() throws IOException {
            File manualCli = tempDir.resolve("manual-bw").toFile();
            assertTrue(manualCli.createNewFile());
            assertTrue(manualCli.setExecutable(true));
            when(configMock.getCliExecutablePath()).thenReturn(manualCli.getAbsolutePath());

            String path = manager.getExecutablePath();

            assertEquals(manualCli.getAbsolutePath(), path);
            verify(manager, never()).provisionExecutable();
        }

        @Test
        @DisplayName("should throw IllegalStateException if provisioning fails")
        void shouldThrowExceptionIfProvisioningFails() {
            when(configMock.getCliExecutablePath()).thenReturn(null);
            doReturn(false).when(manager).provisionExecutable();
            assertThrows(IllegalStateException.class, () -> manager.getExecutablePath());
            verify(manager, times(1)).provisionExecutable();
        }

        @Test
        @DisplayName("should handle whitespace in user-provided path")
        void shouldHandleWhitespaceInUserPath() throws IOException {
            File manualCli = tempDir.resolve("manual bw with spaces").toFile();
            assertTrue(manualCli.createNewFile());
            assertTrue(manualCli.setExecutable(true));
            when(configMock.getCliExecutablePath()).thenReturn("  " + manualCli.getAbsolutePath() + "  ");

            String path = manager.getExecutablePath();

            assertEquals(manualCli.getAbsolutePath(), path);
        }

        @Test
        @DisplayName("should return cached path if executable exists")
        void shouldReturnCachedPathIfExecutableExists() throws Exception {
            when(configMock.getCliExecutablePath()).thenReturn(null); // No manual path
            File executable = new File(tempDir.toFile(), "bw");
            assertTrue(executable.createNewFile(), "Test setup failed: could not create fake executable.");

            Field pathField = BitwardenCLIManager.class.getDeclaredField("executablePath");
            pathField.setAccessible(true);
            pathField.set(manager, executable.getAbsolutePath());

            String path = manager.getExecutablePath();

            assertEquals(executable.getAbsolutePath(), path);
            verify(manager, never()).provisionExecutable();
        }

        @Test
        @DisplayName("should provision executable if path is not cached")
        void shouldProvisionIfCacheIsEmpty() {
            when(configMock.getCliExecutablePath()).thenReturn(null);
            doAnswer(invocation -> {
                        Field pathField = BitwardenCLIManager.class.getDeclaredField("executablePath");
                        pathField.setAccessible(true);
                        pathField.set(manager, "/fake/path/to/bw");
                        return true;
                    })
                    .when(manager)
                    .provisionExecutable();

            String path = manager.getExecutablePath();

            assertEquals("/fake/path/to/bw", path);
            verify(manager, times(1)).provisionExecutable();
        }
    }

    @Nested
    @DisplayName("provisionExecutable() method")
    class ProvisionExecutable {
        @Test
        @DisplayName("should return true if executable already exists")
        void shouldSucceedIfExecutableExists() {
            System.setProperty("os.name", "Linux");
            File binDir = new File(tempDir.toFile(), "bin");
            assertTrue(binDir.mkdirs(), "Test setup failed: could not create bin directory.");
            File executable = new File(binDir, "bw");
            assertDoesNotThrow(executable::createNewFile, "Test setup failed: could not create fake executable.");
            assertTrue(manager.provisionExecutable());
            verify(manager, never()).downloadLatestExecutable();
        }

        @Test
        @DisplayName("should attempt to download if executable is missing")
        void shouldDownloadIfMissing() {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");
            doReturn(true).when(manager).downloadLatestExecutable();

            assertTrue(manager.provisionExecutable());
            verify(manager, times(1)).downloadLatestExecutable();
        }
    }

    @Nested
    @DisplayName("downloadLatestExecutable() method")
    class DownloadLatestExecutable {

        @Test
        @DisplayName("should succeed and set executable path on successful download")
        @SuppressWarnings("unchecked")
        void shouldSucceedAndSetExecutablePath() throws Exception {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "amd64");

            // Mock the HTTP client to simulate a successful download
            HttpClient mockClient = mock(HttpClient.class);
            HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
            mockedProxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);

            // Create a dummy zip file with the executable
            byte[] zipBytes = createTestZipWithContent(new ZipContent("bw", "executable-content".getBytes()));
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(new java.io.ByteArrayInputStream(zipBytes));
            when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            assertTrue(manager.downloadLatestExecutable());

            Field pathField = BitwardenCLIManager.class.getDeclaredField("executablePath");
            pathField.setAccessible(true);
            String executablePath = (String) pathField.get(manager);

            assertNotNull(executablePath);
            assertTrue(executablePath.endsWith("bin" + File.separator + "bw"));
        }

        @Test
        @DisplayName("should return false when download fails with IOException")
        @SuppressWarnings("unchecked")
        void shouldFailOnIOException() throws Exception {
            System.setProperty("os.name", "Windows 10");
            System.setProperty("os.arch", "amd64");

            // Mock the HTTP client to simulate a failed download
            HttpClient mockClient = mock(HttpClient.class);
            mockedProxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);
            when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Network error"));

            assertFalse(manager.downloadLatestExecutable());
        }

        private byte[] createTestZipWithContent(ZipContent... contents) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (ZipContent content : contents) {
                    ZipEntry entry = new ZipEntry(content.name());
                    zos.putNextEntry(entry);
                    zos.write(content.content());
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        }

        private record ZipContent(String name, byte[] content) {}
    }

    @Nested
    @DisplayName("OS detection and architecture support")
    class OsAndArchDetection {

        @Test
        @DisplayName("should detect Windows and return correct executable name")
        void shouldDetectWindows() throws Exception {
            System.setProperty("os.name", "Windows 10");
            assertEquals("bw.exe", invokeGetExecutableName());
        }

        @Test
        @DisplayName("should detect macOS and return correct executable name")
        void shouldDetectMac() throws Exception {
            System.setProperty("os.name", "Mac OS X");
            assertEquals("bw", invokeGetExecutableName());
        }

        @Test
        @DisplayName("should detect Linux and return correct executable name")
        void shouldDetectLinux() throws Exception {
            System.setProperty("os.name", "Linux");
            assertEquals("bw", invokeGetExecutableName());
        }

        @Test
        @DisplayName("should throw exception for unsupported OS")
        void shouldThrowForUnsupportedOs() {
            System.setProperty("os.name", "Solaris");
            Exception exception = assertThrows(InvocationTargetException.class, this::invokeGetExecutableName);
            assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
        }

        @Test
        @DisplayName("should throw exception for unsupported architecture")
        void shouldThrowForUnsupportedArchitecture() {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "arm64"); // an unsupported arch
            Exception exception = assertThrows(InvocationTargetException.class, this::invokeGetDownloadUrl);
            assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
        }

        private String invokeGetExecutableName() throws Exception {
            java.lang.reflect.Method method = BitwardenCLIManager.class.getDeclaredMethod("getExecutableName");
            method.setAccessible(true);
            return (String) method.invoke(manager);
        }

        private void invokeGetDownloadUrl() throws Exception {
            java.lang.reflect.Method method = BitwardenCLIManager.class.getDeclaredMethod("getDownloadUrl");
            method.setAccessible(true);
            method.invoke(manager);
        }
    }

    @Nested
    @DisplayName("downloadAndExtract() helper method")
    class DownloadAndExtract {
        @Test
        @DisplayName("should extract correct file from zip and set executable")
        @SuppressWarnings("unchecked")
        void shouldExtractCorrectFileFromZip() throws Exception {
            System.setProperty("os.name", "Linux");
            String executableName = "bw";
            byte[] executableContent = "this-is-a-linux-binary".getBytes();
            File targetFile = tempDir.resolve("bw_extracted").toFile();

            byte[] zipBytes = createTestZipWithContent(
                    new ZipContent("some-other-file.txt", "hello".getBytes()),
                    new ZipContent(executableName, executableContent));
            URI fakeUri = new URI("https://fake.bitwarden.com/download.zip");

            HttpClient mockClient = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);

            mockedProxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);

            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(new java.io.ByteArrayInputStream(zipBytes));

            when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URI.class, File.class);
            method.setAccessible(true);
            method.invoke(manager, fakeUri, targetFile);

            assertTrue(targetFile.exists(), "Target file should have been created");
            assertTrue(targetFile.canExecute(), "Target file should be executable");
            assertArrayEquals(executableContent, Files.readAllBytes(targetFile.toPath()), "Content should match");
        }

        @Test
        @DisplayName("should throw IOException if executable not found in zip")
        @SuppressWarnings("unchecked")
        void shouldThrowIfExecutableNotFound() throws Exception {
            System.setProperty("os.name", "Linux");
            byte[] zipBytes = createTestZipWithContent(new ZipContent("some-other-file.txt", "hello".getBytes()));
            URI fakeUri = new URI("https://fake.bitwarden.com/download.zip");
            File targetFile = tempDir.resolve("bw_extracted").toFile();

            HttpClient mockClient = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<java.io.InputStream> mockResponse = mock(HttpResponse.class);
            mockedProxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(new java.io.ByteArrayInputStream(zipBytes));
            when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URI.class, File.class);
            method.setAccessible(true);

            Exception exception =
                    assertThrows(InvocationTargetException.class, () -> method.invoke(manager, fakeUri, targetFile));

            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Could not find 'bw' or 'bw.exe'"));
        }

        @Test
        @DisplayName("should throw IOException on non-200 HTTP status")
        @SuppressWarnings("unchecked")
        void shouldThrowOnNon200Status() throws Exception {
            URI fakeUri = new URI("https://fake.bitwarden.com/download.zip");
            File targetFile = tempDir.resolve("bw_extracted").toFile();

            HttpClient mockClient = mock(HttpClient.class);
            HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
            mockedProxy.when(ProxyConfiguration::newHttpClient).thenReturn(mockClient);

            when(mockResponse.statusCode()).thenReturn(404);
            when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URI.class, File.class);
            method.setAccessible(true);

            Exception exception =
                    assertThrows(InvocationTargetException.class, () -> method.invoke(manager, fakeUri, targetFile));

            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Failed to download file"));
        }

        private record ZipContent(String name, byte[] content) {}

        private byte[] createTestZipWithContent(ZipContent... contents) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (ZipContent content : contents) {
                    ZipEntry entry = new ZipEntry(content.name());
                    zos.putNextEntry(entry);
                    zos.write(content.content());
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        }
    }
}
