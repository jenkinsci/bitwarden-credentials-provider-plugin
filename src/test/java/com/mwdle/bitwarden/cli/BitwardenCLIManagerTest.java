package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mwdle.bitwarden.PluginDirectoryProvider;
import hudson.ExtensionList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

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

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private BitwardenCLIManager manager;
    private String originalOsName;

    @BeforeEach
    void setUp() {
        originalOsName = System.getProperty("os.name");
        manager = spy(new BitwardenCLIManager());

        Jenkins jenkinsMock = mock(Jenkins.class);
        @SuppressWarnings("unchecked")
        ExtensionList<BitwardenCLIManager> extensionList = mock(ExtensionList.class);
        when(extensionList.get(0)).thenReturn(manager);
        when(jenkinsMock.getExtensionList(BitwardenCLIManager.class)).thenReturn(extensionList);
        mockedJenkins = mockStatic(Jenkins.class);
        mockedJenkins.when(Jenkins::get).thenReturn(jenkinsMock);
        mockedJenkins.when(BitwardenCLIManager::getInstance).thenReturn(manager);

        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        mockedPluginDir.when(PluginDirectoryProvider::getPluginDataDirectory).thenReturn(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        mockedJenkins.close();
        mockedPluginDir.close();
        System.setProperty("os.name", originalOsName);
    }

    @Nested
    @DisplayName("getExecutablePath() method")
    class GetExecutablePath {
        @Test
        @DisplayName("should return cached path if executable exists")
        void shouldReturnCachedPathIfExecutableExists() throws Exception {
            File executable = new File(tempDir.toFile(), "bw");
            executable.createNewFile();

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

        @Test
        @DisplayName("should throw IllegalStateException if provisioning fails")
        void shouldThrowExceptionIfProvisioningFails() {
            doReturn(false).when(manager).provisionExecutable();
            assertThrows(IllegalStateException.class, () -> manager.getExecutablePath());
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
            binDir.mkdirs();
            File executable = new File(binDir, "bw");
            assertTrue(executable.isFile() || assertDoesNotThrow(executable::createNewFile));

            assertTrue(manager.provisionExecutable());
            verify(manager, never()).downloadLatestExecutable();
        }

        @Test
        @DisplayName("should attempt to download if executable is missing")
        void shouldDownloadIfMissing() {
            System.setProperty("os.name", "Linux");
            doReturn(true).when(manager).downloadLatestExecutable();

            assertTrue(manager.provisionExecutable());
            verify(manager, times(1)).downloadLatestExecutable();
        }
    }

    @Nested
    @DisplayName("downloadAndExtract() helper method")
    class DownloadAndExtract {
        @Test
        @DisplayName("should extract correct file from zip and set executable")
        void shouldExtractCorrectFileFromZip() throws Exception {
            System.setProperty("os.name", "Linux");
            String executableName = "bw";
            byte[] executableContent = "this-is-a-linux-binary".getBytes();
            File targetFile = tempDir.resolve("bw_extracted").toFile();

            byte[] zipBytes = createTestZipWithContent(
                    new ZipContent("some-other-file.txt", "hello".getBytes()),
                    new ZipContent(executableName, executableContent));
            URL fakeUrl = createFakeUrlWithContent(zipBytes);

            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URL.class, File.class);
            method.setAccessible(true);
            method.invoke(manager, fakeUrl, targetFile);

            assertTrue(targetFile.exists(), "Target file should have been created");
            assertTrue(targetFile.canExecute(), "Target file should be executable");
            assertArrayEquals(executableContent, Files.readAllBytes(targetFile.toPath()), "Content should match");
        }

        @Test
        @DisplayName("should throw IOException if executable not found in zip")
        void shouldThrowIfExecutableNotFound() throws Exception {
            System.setProperty("os.name", "Linux");
            byte[] zipBytes = createTestZipWithContent(new ZipContent("some-other-file.txt", "hello".getBytes()));
            URL fakeUrl = createFakeUrlWithContent(zipBytes);
            File targetFile = tempDir.resolve("bw_extracted").toFile();
            java.lang.reflect.Method method =
                    BitwardenCLIManager.class.getDeclaredMethod("downloadAndExtract", URL.class, File.class);
            method.setAccessible(true);

            Exception exception =
                    assertThrows(InvocationTargetException.class, () -> method.invoke(manager, fakeUrl, targetFile));

            assertInstanceOf(IOException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Could not find 'bw' or 'bw.exe'"));
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

        private URL createFakeUrlWithContent(byte[] content) throws Exception {
            URL urlMock = mock(URL.class);
            when(urlMock.openStream()).thenReturn(new java.io.ByteArrayInputStream(content));
            return urlMock;
        }
    }
}
