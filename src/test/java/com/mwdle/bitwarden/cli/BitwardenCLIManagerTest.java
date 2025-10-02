// package com.mwdle.bitwarden;
//
// import static org.junit.jupiter.api.Assertions.*;
// import static org.mockito.Mockito.*;
//
// import java.io.ByteArrayInputStream;
// import java.io.ByteArrayOutputStream;
// import java.io.File;
// import java.io.IOException;
// import java.net.URL;
// import java.nio.file.Path;
// import java.util.zip.ZipEntry;
// import java.util.zip.ZipOutputStream;
// import jenkins.model.Jenkins;
// import org.junit.jupiter.api.*;
// import org.junit.jupiter.api.io.TempDir;
// import org.mockito.MockedConstruction;
// import org.mockito.MockedStatic;
//
/// **
// * Unit tests for the BitwardenCLIManager.
// * Uses Mockito's static mocking to isolate the class from Jenkins and the file system.
// */
// @DisplayName("BitwardenCLIManager")
// public class BitwardenCLIManagerTest {
//
//    @TempDir
//    Path tempDir;
//
//    private MockedStatic<Jenkins> mockedJenkins;
//    private File pluginBinDir;
//
//    @BeforeEach
//    void setUp() {
//        Jenkins jenkinsMock = mock(Jenkins.class);
//        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());
//
//        mockedJenkins = mockStatic(Jenkins.class);
//        mockedJenkins.when(Jenkins::get).thenReturn(jenkinsMock);
//
//        pluginBinDir = tempDir.resolve("plugins/bitwarden-credentials-provider-plugin/bin")
//                .toFile();
//    }
//
//    @AfterEach
//    void tearDown() {
//        mockedJenkins.close();
//    }
//
//    private String getExpectedExecutableName() {
//        String osName = System.getProperty("os.name").toLowerCase();
//        if (osName.contains("win")) {
//            return "bw.exe";
//        }
//        return "bw";
//    }
//
//    private byte[] createFakeZipBytes(String entryName, String content) throws IOException {
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
//            ZipEntry entry = new ZipEntry(entryName);
//            zos.putNextEntry(entry);
//            zos.write(content.getBytes());
//            zos.closeEntry();
//        }
//        return baos.toByteArray();
//    }
//
//    @Nested
//    @DisplayName("Constructor Logic")
//    class ConstructorTests {
//
//        @Test
//        @DisplayName("should download and extract executable when it does not exist")
//        void shouldDownloadAndExtractWhenNotExists() throws IOException {
//            String expectedExeName = getExpectedExecutableName();
//            byte[] fakeZipBytes = createFakeZipBytes(expectedExeName, "fake-executable-content");
//
//            try (MockedConstruction<URL> ignored =
//                    mockConstruction(URL.class, (mock, context) -> when(mock.openStream())
//                            .thenReturn(new ByteArrayInputStream(fakeZipBytes)))) {
//
//                BitwardenCLIManager manager = new BitwardenCLIManager();
//
//                File expectedExecutable = new File(pluginBinDir, expectedExeName);
//                assertTrue(expectedExecutable.exists(), "Executable should have been extracted.");
//                assertTrue(expectedExecutable.canExecute(), "Executable should have execute permissions set.");
//                assertEquals(expectedExecutable.getAbsolutePath(), manager.getExecutablePath());
//            }
//        }
//
//        @Test
//        @DisplayName("should throw an exception if executable is not found in zip")
//        void shouldThrowExceptionWhenExecutableNotFoundInZip() throws IOException {
//            byte[] badZipBytes = createFakeZipBytes("wrong-file-name.txt", "some-content");
//
//            try (MockedConstruction<URL> ignored =
//                    mockConstruction(URL.class, (mock, context) -> when(mock.openStream())
//                            .thenReturn(new ByteArrayInputStream(badZipBytes)))) {
//
//                RuntimeException exception = assertThrows(
//                        RuntimeException.class,
//                        BitwardenCLIManager::new,
//                        "Constructor should fail when the zip file is invalid.");
//
//                assertTrue(exception.getCause().getMessage().contains("Could not find 'bw' or 'bw.exe' executable"));
//            }
//        }
//
//        @Test
//        @DisplayName("should skip download if executable already exists")
//        void shouldSkipDownloadWhenExecutableExists() throws IOException {
//            assertTrue(pluginBinDir.mkdirs());
//            File fakeExecutable = new File(pluginBinDir, getExpectedExecutableName());
//            assertTrue(fakeExecutable.createNewFile(), "Failed to create fake executable for test.");
//
//            BitwardenCLIManager manager = assertDoesNotThrow(
//                    BitwardenCLIManager::new, "Constructor should not fail when the executable already exists.");
//
//            assertEquals(fakeExecutable.getAbsolutePath(), manager.getExecutablePath());
//        }
//
//        @Test
//        @DisplayName("should determine correct executable name for Windows")
//        void shouldDetermineWindowsExecutableName() throws IOException {
//            String originalOs = System.getProperty("os.name");
//            System.setProperty("os.name", "Windows 11");
//            try {
//                assertTrue(pluginBinDir.mkdirs());
//                File fakeExecutable = new File(pluginBinDir, "bw.exe");
//                assertTrue(fakeExecutable.createNewFile());
//
//                BitwardenCLIManager manager = new BitwardenCLIManager();
//                assertEquals(fakeExecutable.getAbsolutePath(), manager.getExecutablePath());
//            } finally {
//                System.setProperty("os.name", originalOs);
//            }
//        }
//    }
//
//    @Nested
//    @DisplayName("Cleanup Logic")
//    class CleanupTests {
//
//        @Test
//        @DisplayName("should delete the executable on cleanup")
//        void shouldDeleteExecutableOnCleanup() throws IOException {
//            assertTrue(pluginBinDir.mkdirs());
//            File fakeExecutable = new File(pluginBinDir, getExpectedExecutableName());
//            assertTrue(fakeExecutable.createNewFile());
//
//            BitwardenCLIManager manager = new BitwardenCLIManager();
//            assertTrue(fakeExecutable.exists(), "Fake executable should exist before cleanup.");
//
//            manager.cleanup();
//
//            assertFalse(fakeExecutable.exists(), "The executable file should be deleted after cleanup.");
//        }
//    }
// }
