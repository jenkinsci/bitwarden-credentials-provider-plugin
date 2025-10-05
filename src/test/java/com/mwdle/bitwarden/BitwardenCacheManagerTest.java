package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.google.common.cache.LoadingCache;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenCacheManager class.
 * <p>
 * This test suite isolates the BitwardenCacheManager to verify its core responsibilities:
 * - Triggering cache updates on startup.
 * - Providing access to metadata via the getMetadata() method.
 * - Handling manual cache updates and invalidations.
 * - Acknowledges that the private getCache() method's I/O logic is untestable in isolation
 * without modifying the source code, and focuses on the public API.
 */
@DisplayName("BitwardenCacheManager")
class BitwardenCacheManagerTest {

    @TempDir
    Path tempDir;

    // Mocks for static Jenkins and plugin classes
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<Timer> mockedTimer;
    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private MockedStatic<BitwardenSessionManager> mockedSessionManager;
    private MockedStatic<BitwardenCLI> mockedCli;

    // Mock instances of dependencies
    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ScheduledExecutorService executorMock;

    @Mock
    private BitwardenConfig configMock;

    @Mock
    private BitwardenSessionManager sessionManagerMock;

    @Mock
    private LoadingCache<String, List<BitwardenItemMetadata>> cacheMock;

    @Mock
    private Authentication authenticationMock;

    private BitwardenCacheManager cacheManager;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        // Initialize all static mocks
        mockedJenkins = mockStatic(Jenkins.class);
        mockedTimer = mockStatic(Timer.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        mockedSessionManager = mockStatic(BitwardenSessionManager.class);
        mockedCli = mockStatic(BitwardenCLI.class);

        // Configure mocks to return singleton instances
        when(Jenkins.get()).thenReturn(jenkinsMock);
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(authenticationMock);
        when(Timer.get()).thenReturn(executorMock);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);
        when(PluginDirectoryProvider.getPluginDataDirectory()).thenReturn(tempDir.toFile());
        when(BitwardenSessionManager.getInstance()).thenReturn(sessionManagerMock);
        when(configMock.getCacheDuration()).thenReturn(5);

        cacheManager = new BitwardenCacheManager();

        // Inject a mock cache instance for all tests to bypass the untestable private getCache() method
        injectMockCache();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedTimer.close();
        mockedConfig.close();
        mockedPluginDir.close();
        mockedSessionManager.close();
        mockedCli.close();
        closeable.close();
    }

    private void injectMockCache() throws NoSuchFieldException, IllegalAccessException {
        Field cacheField = BitwardenCacheManager.class.getDeclaredField("itemMetadataCache");
        cacheField.setAccessible(true);
        cacheField.set(cacheManager, cacheMock);
    }

    @Nested
    @DisplayName("triggerStartupCacheUpdate()")
    class TriggerStartupCacheUpdate {

        @Test
        @DisplayName("should submit an update task when configured")
        void shouldSubmitUpdateTaskWhenConfigured() {
            when(configMock.isConfigured()).thenReturn(true);
            cacheManager.triggerStartupCacheUpdate();
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should not submit an update task when not configured")
        void shouldNotSubmitUpdateTaskWhenNotConfigured() {
            when(configMock.isConfigured()).thenReturn(false);
            cacheManager.triggerStartupCacheUpdate();
            verify(executorMock, never()).submit(any(Runnable.class));
        }
    }

    @Nested
    @DisplayName("Public API Tests")
    class PublicApiTests {

        @Test
        @DisplayName("getMetadata() should return cached data and trigger background refresh")
        void getMetadataShouldReturnCachedData() throws ExecutionException {
            // GIVEN
            List<BitwardenItemMetadata> expectedMetadata = List.of(mock(BitwardenItemMetadata.class));
            when(cacheMock.getIfPresent(anyString())).thenReturn(expectedMetadata);
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

            // WHEN
            List<BitwardenItemMetadata> actualMetadata = cacheManager.getMetadata();
            verify(executorMock).submit(taskCaptor.capture());
            taskCaptor.getValue().run(); // Manually run the background task

            // THEN
            assertEquals(expectedMetadata, actualMetadata);
            verify(cacheMock, times(1)).get(anyString()); // Verify background refresh
        }

        @Test
        @DisplayName("getMetadata() should return empty list and trigger background refresh when cache is empty")
        void getMetadataShouldReturnEmptyListWhenCacheIsEmpty() throws ExecutionException {
            // GIVEN
            when(cacheMock.getIfPresent(anyString())).thenReturn(null);
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

            // WHEN
            List<BitwardenItemMetadata> actualMetadata = cacheManager.getMetadata();
            verify(executorMock).submit(taskCaptor.capture());
            taskCaptor.getValue().run();

            // THEN
            assertTrue(actualMetadata.isEmpty());
            verify(cacheMock, times(1)).get(anyString());
        }

        @Test
        @DisplayName("updateCache() should call refresh on the underlying cache")
        void updateCacheShouldCallRefresh() {
            // WHEN
            cacheManager.updateCache();

            // THEN
            verify(cacheMock, times(1)).refresh(anyString());
        }

        @Test
        @DisplayName("invalidateCache() should call invalidate on the underlying cache")
        void invalidateCacheShouldCallInvalidate() {
            // WHEN
            cacheManager.invalidateCache();

            // THEN
            verify(cacheMock, times(1)).invalidate(anyString());
        }
    }
}
