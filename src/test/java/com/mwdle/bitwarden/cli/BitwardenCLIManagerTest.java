package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.mwdle.bitwarden.PluginDirectoryProvider;
import hudson.ExtensionList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Unit tests for the BitwardenCLIManager class.
 * <p>
 * This test suite verifies the logic of the CLI manager in isolation from the
 * network and file system by using spies and mocking static dependencies.
 */
@DisplayName("BitwardenCLIManager")
class BitwardenCLIManagerTest {
    @TempDir
    Path tempDir;

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private BitwardenCLIManager manager;
    private String originalOsName;

    @BeforeEach
    void setUp() {
        originalOsName = System.getProperty("os.name");

        // Use a spy to allow partial mocking of the manager, so we can test its
        // internal logic while stubbing out methods that perform real I/O.
        manager = spy(new BitwardenCLIManager());

        // Mock the Jenkins singleton and ExtensionList to return our spy.
        Jenkins jenkinsMock = mock(Jenkins.class);
        @SuppressWarnings("unchecked")
        ExtensionList<BitwardenCLIManager> extensionList = mock(ExtensionList.class);
        when(extensionList.get(0)).thenReturn(manager);
        when(jenkinsMock.getExtensionList(BitwardenCLIManager.class)).thenReturn(extensionList);
        mockedJenkins = mockStatic(Jenkins.class);
        mockedJenkins.when(Jenkins::get).thenReturn(jenkinsMock);

        // Mock the PluginDirectoryProvider to use our temporary directory.
        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        mockedPluginDir.when(PluginDirectoryProvider::getPluginDataDirectory).thenReturn(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        mockedJenkins.close();
        mockedPluginDir.close();
        // Restore the original system property to avoid side effects in other tests.
        System.setProperty("os.name", originalOsName);
    }

    @Nested
    @DisplayName("OS Detection and Configuration")
    class OsDetection {
        @ParameterizedTest(name = "should correctly identify {0}")
        @CsvSource({
                "Windows 10, bw.exe, https://bitwarden.com/download/?app=cli&platform=windows",
                "Linux, bw, https://bitwarden.com/download/?app=cli&platform=linux",
                "Mac OS X, bw, https://bitwarden.com/download/?app=cli&platform=macos"
        })
        void shouldReturnCorrectConfigForOs(String osName, String expectedExe, String expectedUrl) throws Exception {
            System.setProperty("os.name", osName);

            // Access and test private methods via reflection.
            java.lang.reflect.Method getExecutableNameMethod =
                    BitwardenCLIManager.class.getDeclaredMethod("getExecutableName");
            getExecutableNameMethod.setAccessible(true);
            String actualExe = (String) getExecutableNameMethod.invoke(manager);

            java.lang.reflect.Method getDownloadUrlMethod =
                    BitwardenCLIManager.class.getDeclaredMethod("getDownloadUrl");
            getDownloadUrlMethod.setAccessible(true);
            String actualUrl = (String) getDownloadUrlMethod.invoke(manager);

            assertEquals(expectedExe, actualExe, "Executable name should match for " + osName);
            assertEquals(expectedUrl, actualUrl, "Download URL should match for " + osName);
        }
    }

    @Nested
    @DisplayName("getExecutablePath() method")
    class GetExecutablePath {
        @Test
        @DisplayName("should return cached path if executable exists")
        void shouldReturnCachedPathIfExecutableExists() throws Exception {
            File binDir = new File(tempDir.toFile(), "bin");
            binDir.mkdirs();
            File executable = new File(binDir, "bw");
            executable.createNewFile();

            // Set the private `executablePath` field via reflection to simulate a cached state.
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
            // Stub provisionExecutable to return true and set the internal path field.
            doAnswer(invocation -> {
                Field pathField = BitwardenCLIManager.class.getDeclaredField("executablePath");
                pathField.setAccessible(true);
                pathField.set(manager, "/fake/path/to/bw");
                return true;
            })
                    .when(manager)
                    .provisionExecutable();

            manager.getExecutablePath();

            verify(manager, times(1)).provisionExecutable();
        }

        @Test
        @DisplayName("should throw IllegalStateException if provisioning fails")
        void shouldThrowExceptionIfProvisioningFails() {
            // Stub provisionExecutable to simulate a failure.
            doReturn(false).when(manager).provisionExecutable();

            assertThrows(IllegalStateException.class, () -> manager.getExecutablePath());
            verify(manager, times(1)).provisionExecutable();
        }
    }

    @Nested
    @DisplayName("provisionExecutable() method")
    class ProvisionExecutable {
        @Test
        @DisplayName("should return true and set path if executable already exists")
        void shouldSucceedIfExecutableExists() throws Exception {
            System.setProperty("os.name", "Linux");
            File binDir = new File(tempDir.toFile(), "bin");
            binDir.mkdirs();
            File executable = new File(binDir, "bw");
            executable.createNewFile();

            assertTrue(manager.provisionExecutable());

            // Verify the internal state was correctly updated.
            Field pathField = BitwardenCLIManager.class.getDeclaredField("executablePath");
            pathField.setAccessible(true);
            assertEquals(executable.getAbsolutePath(), pathField.get(manager));

            // Verify the expensive download operation was correctly skipped.
            verify(manager, never()).downloadLatestExecutable();
        }

        @Test
        @DisplayName("should attempt to download if executable is missing")
        void shouldDownloadIfMissing() {
            // Stub the download method to simulate success.
            doReturn(true).when(manager).downloadLatestExecutable();

            assertTrue(manager.provisionExecutable());
            verify(manager, times(1)).downloadLatestExecutable();
        }

        @Test
        @DisplayName("should throw RuntimeException if bin directory cannot be created")
        void shouldThrowRuntimeExceptionWhenMkdirsFails() {
            // GIVEN: The executable does not exist, triggering a download path.
            System.setProperty("os.name", "Linux");
            File pluginDir = tempDir.toFile();
            File binDir = new File(pluginDir, "bin");

            // Use a spy to intercept the call to mkdirs()
            File binDirSpy = spy(binDir);
            // When mkdirs() is called on our spy, pretend it failed.
            doReturn(false).when(binDirSpy).mkdirs();

            // To inject our spy, we need to mock the File constructor.
            // This is an advanced technique, but necessary for this edge case.
            try (MockedConstruction<File> mockedFile = mockConstruction(File.class, (mock, context) -> {
                // If the constructor is called with our "bin" path, return our spy instead.
                if (context.arguments().get(1).equals("bin")) {
                    when(mock.exists()).thenReturn(false); // Pretend the dir doesn't exist
                    when(mock.mkdirs()).thenReturn(false); // Pretend mkdirs() fails
                    // You can also directly return the spy, but mocking the behavior is cleaner.
                }
            })) {

                // WHEN & THEN
                RuntimeException exception = assertThrows(
                        RuntimeException.class,
                        () -> manager.provisionExecutable(),
                        "Should fail with a RuntimeException if the directory cannot be created."
                );

                assertTrue(exception.getMessage().contains("Could not create plugin bin directory"));
            }
        }
    }

    @Nested
    @DisplayName("downloadLatestExecutable() method")
    class DownloadLatestExecutable {
        @Test
        @DisplayName("should orchestrate download and return true on success")
        void shouldOrchestrateDownloadAndSucceed() throws Exception {
            System.setProperty("os.name", "Linux");

            // Stub the private `downloadAndExtract` method to prevent any real I/O.
            // This requires the method to be package-private.
            doNothing().when(manager).downloadAndExtract(any(URL.class), any(File.class));

            boolean result = manager.downloadLatestExecutable();

            assertTrue(result);

            // Verify the orchestration logic: check that the correct URL and target file
            // were passed to the private helper method.
            File expectedFile = new File(tempDir.toFile(), "bin/bw");
            URL expectedUrl = new URI("https://bitwarden.com/download/?app=cli&platform=linux").toURL();
            verify(manager, times(1)).downloadAndExtract(eq(expectedUrl), eq(expectedFile));
        }

        @Test
        @DisplayName("should return false on failure")
        void shouldReturnFalseOnFailure() throws Exception {
            // Stub the helper method to throw an exception, simulating a failed download.
            // This requires the method to be package-private.
            doThrow(new IOException("Download failed"))
                    .when(manager)
                    .downloadAndExtract(any(URL.class), any(File.class));

            boolean result = manager.downloadLatestExecutable();

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("downloadAndExtract() helper method")
    class DownloadAndExtract {
        @Test
        @DisplayName("should extract correct file from zip and set executable")
        void shouldExtractCorrectFileFromZip() throws Exception {
            // GIVEN
            System.setProperty("os.name", "Linux");
            String executableName = "bw";
            byte[] executableContent = "this-is-a-linux-binary".getBytes();
            File targetFile = tempDir.resolve("bw_extracted").toFile();

            // Create an in-memory zip with multiple files, one of which is our target executable.
            byte[] zipBytes = createTestZipWithContent(
                    new ZipContent("some-other-file.txt", "hello".getBytes()),
                    new ZipContent(executableName, executableContent));
            URL fakeUrl = createFakeUrlWithContent(zipBytes);

            // WHEN: Invoke the private method using reflection.
            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URL.class, File.class);
            method.setAccessible(true);
            method.invoke(manager, fakeUrl, targetFile);

            // THEN
            assertTrue(targetFile.exists(), "Target file should have been created");
            assertTrue(targetFile.canExecute(), "Target file should be executable");
            assertArrayEquals(executableContent, Files.readAllBytes(targetFile.toPath()), "Content should match");
        }

        @Test
        @DisplayName("should throw IOException if executable not found in zip")
        void shouldThrowIfExecutableNotFound() throws Exception {
            // GIVEN: an in-memory zip file WITHOUT the target executable.
            System.setProperty("os.name", "Linux");
            byte[] zipBytes = createTestZipWithContent(new ZipContent("some-other-file.txt", "hello".getBytes()));
            URL fakeUrl = createFakeUrlWithContent(zipBytes);
            File targetFile = tempDir.resolve("bw_extracted").toFile();
            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URL.class, File.class);
            method.setAccessible(true);

            // WHEN & THEN
            Exception exception = assertThrows(InvocationTargetException.class, () -> method.invoke(manager, fakeUrl, targetFile));

            // The exception will be wrapped in an InvocationTargetException by reflection.
            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Could not find 'bw' or 'bw.exe'"));
        }

        /** A record to hold fake zip file entry data. */
        private record ZipContent(String name, byte[] content) {}

        /** Creates an in-memory zip file from the given content for testing. */
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

        /** Creates a mock URL that returns a predefined byte array instead of making a network call. */
        private URL createFakeUrlWithContent(byte[] content) throws Exception {
            URL urlMock = mock(URL.class);
            when(urlMock.openStream()).thenReturn(new java.io.ByteArrayInputStream(content));
            return urlMock;
        }
    }
}