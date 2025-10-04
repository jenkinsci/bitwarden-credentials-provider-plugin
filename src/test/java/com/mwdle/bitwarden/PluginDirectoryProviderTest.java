package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the PluginDirectoryProvider class.
 * <p>
 * This test suite verifies that the provider correctly and safely creates
 * and returns the dedicated plugin data directory under various conditions.
 */
@DisplayName("PluginDirectoryProvider")
class PluginDirectoryProviderTest {

    @TempDir
    Path tempDir;

    @Mock
    private Jenkins jenkinsMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        // Reset the static field in PluginDirectoryProvider before each test
        Field field = PluginDirectoryProvider.class.getDeclaredField("pluginDirectory");
        field.setAccessible(true);
        field.set(null, null);

        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        // Default to a writable directory for most tests
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        closeable.close();
    }

    @Test
    @DisplayName("should create directory on first call if it does not exist")
    void shouldCreateDirectoryOnFirstCall() {
        // GIVEN: The plugin directory does not exist yet
        File expectedDir = new File(tempDir.toFile(), "bitwarden-credentials-provider-data");
        assertFalse(expectedDir.exists());

        // WHEN
        File result = PluginDirectoryProvider.getPluginDataDirectory();

        // THEN
        assertEquals(expectedDir.getAbsolutePath(), result.getAbsolutePath());
        assertTrue(expectedDir.exists());
        assertTrue(expectedDir.isDirectory());
    }

    @Test
    @DisplayName("should return cached directory on subsequent calls")
    void shouldReturnCachedDirectoryOnSubsequentCalls() {
        // GIVEN: The directory is fetched once to populate the cache
        File firstResult = PluginDirectoryProvider.getPluginDataDirectory();
        assertTrue(firstResult.exists());

        // WHEN: We call it a second time
        File secondResult = PluginDirectoryProvider.getPluginDataDirectory();

        // THEN: The result should be the same instance, and the creation logic
        // in Jenkins.get() should only have been called once.
        assertSame(firstResult, secondResult);
        verify(jenkinsMock, times(1)).getRootDir();
    }

    @Test
    @DisplayName("should return existing directory if it already exists")
    void shouldReturnExistingDirectory() {
        // GIVEN: The plugin directory already exists
        File expectedDir = new File(tempDir.toFile(), "bitwarden-credentials-provider-data");
        assertTrue(expectedDir.mkdirs());

        // WHEN
        File result = PluginDirectoryProvider.getPluginDataDirectory();

        // THEN
        assertEquals(expectedDir.getAbsolutePath(), result.getAbsolutePath());
        // Verify that mkdirs was not called by our code
        // We can't spy on File, so we check that Jenkins.get() was only called once.
        verify(jenkinsMock, times(1)).getRootDir();
    }

    @Test
    @DisplayName("should throw RuntimeException if directory creation fails")
    void shouldThrowRuntimeExceptionOnMkdirsFailure() throws IOException {
        // GIVEN: A file exists in place of the Jenkins root directory.
        // This will cause any attempt to create a subdirectory within it to fail.
        File blockingFile = tempDir.resolve("blocking-file.txt").toFile();
        assertTrue(blockingFile.createNewFile());
        when(jenkinsMock.getRootDir()).thenReturn(blockingFile);

        // WHEN & THEN: An exception should be thrown because mkdirs() will fail
        RuntimeException exception =
                assertThrows(RuntimeException.class, PluginDirectoryProvider::getPluginDataDirectory);
        assertTrue(exception.getMessage().contains("Could not create plugin directory"));
    }
}
