package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.common.cache.LoadingCache;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.util.Secret;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
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
class CacheManagerTest {

    @TempDir
    Path tempDir;

    // Mocks for static Jenkins and plugin classes
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<Timer> mockedTimer;
    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<PluginDirectoryProvider> mockedPluginDir;
    private MockedStatic<SessionManager> mockedSessionManager;
    private MockedStatic<BitwardenCli> mockedCli;
    private MockedStatic<Secret> mockedStaticSecret;

    // Mock instances of dependencies
    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ScheduledExecutorService executorMock;

    @Mock
    private BitwardenConfig configMock;

    @Mock
    private SessionManager sessionManagerMock;

    @Mock
    private LoadingCache<String, List<BitwardenItemMetadata>> cacheMock;

    @Mock
    private Authentication authenticationMock;

    @Mock
    private Secret mockSecret;

    @Mock
    private ExtensionList<CacheManager> extensionListMock;

    private CacheManager cacheManager;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        // Initialize all static mocks
        mockedJenkins = mockStatic(Jenkins.class);
        mockedTimer = mockStatic(Timer.class);
        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedPluginDir = mockStatic(PluginDirectoryProvider.class);
        mockedSessionManager = mockStatic(SessionManager.class);
        mockedCli = mockStatic(BitwardenCli.class);

        // Mock static Secret.toString for BitwardenCLI.sync
        mockedStaticSecret = mockStatic(Secret.class);
        mockedStaticSecret.when(() -> Secret.toString(any())).thenReturn("mock-secret");

        // Configure mocks to return singleton instances
        when(Jenkins.get()).thenReturn(jenkinsMock);
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(authenticationMock);
        when(Timer.get()).thenReturn(executorMock);
        when(BitwardenConfig.getInstance()).thenReturn(configMock);
        when(PluginDirectoryProvider.getPluginDataDirectory()).thenReturn(tempDir.toFile());
        when(SessionManager.getInstance()).thenReturn(sessionManagerMock);
        when(configMock.getCacheDuration()).thenReturn(5);

        cacheManager = new CacheManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedTimer.close();
        mockedConfig.close();
        mockedPluginDir.close();
        mockedSessionManager.close();
        mockedCli.close();
        if (mockedStaticSecret != null) {
            mockedStaticSecret.close();
        }
        closeable.close();
    }

    private void injectMockCache() throws NoSuchFieldException, IllegalAccessException {
        Field cacheField = CacheManager.class.getDeclaredField("itemMetadataCache");
        cacheField.setAccessible(true);
        cacheField.set(cacheManager, cacheMock);
    }

    /**
     * Resets the cache manager's internal cache to null, forcing re-initialization.
     */
    private void forceCacheReinitialization() throws NoSuchFieldException, IllegalAccessException {
        Field cacheField = CacheManager.class.getDeclaredField("itemMetadataCache");
        cacheField.setAccessible(true);
        cacheField.set(cacheManager, null);
    }

    @Nested
    @DisplayName("getInstance()")
    class GetInstance {
        @Test
        @DisplayName("should return singleton from ExtensionList")
        void getInstanceShouldReturnSingleton() {
            // GIVEN
            when(jenkinsMock.getExtensionList(CacheManager.class)).thenReturn(extensionListMock);
            when(extensionListMock.get(0)).thenReturn(cacheManager);

            // WHEN
            CacheManager instance = CacheManager.getInstance();

            // THEN
            assertEquals(cacheManager, instance);
            verify(jenkinsMock).getExtensionList(CacheManager.class);
            verify(extensionListMock).get(0);
        }
    }

    @Nested
    @DisplayName("triggerStartupCacheUpdate()")
    class TriggerStartupCacheUpdate {

        @BeforeEach
        void setUpNested() throws NoSuchFieldException, IllegalAccessException {
            // Inject mock cache for *these tests only*
            injectMockCache();
        }

        @Test
        @DisplayName("should submit an update task when configured")
        void shouldSubmitUpdateTaskWhenConfigured() {
            when(configMock.isConfigured()).thenReturn(true);
            cacheManager.refreshCacheOnStartup();
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should not submit an update task when not configured")
        void shouldNotSubmitUpdateTaskWhenConfigured() {
            when(configMock.isConfigured()).thenReturn(false);
            cacheManager.refreshCacheOnStartup();
            verify(executorMock, never()).submit(any(Runnable.class));
        }
    }

    @Nested
    @DisplayName("Public API Tests")
    class PublicApiTests {

        @BeforeEach
        void setUpNested() throws NoSuchFieldException, IllegalAccessException {
            // Inject mock cache for *these tests only*
            injectMockCache();
        }

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
        @DisplayName("getMetadata() should log background refresh failure")
        void getMetadataShouldLogBackgroundRefreshFailure() throws ExecutionException {
            // GIVEN
            // This test covers the catch block in getMetadata's submitted lambda
            when(cacheMock.get(anyString())).thenThrow(new ExecutionException("Cache load failed", null));
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

            // WHEN
            List<BitwardenItemMetadata> actualMetadata = cacheManager.getMetadata();
            verify(executorMock).submit(taskCaptor.capture());

            // THEN: The runnable should catch its own exception and not throw
            assertDoesNotThrow(() -> taskCaptor.getValue().run());
            assertTrue(actualMetadata.isEmpty());
        }

        @Test
        @DisplayName("updateCache() should call refresh on the underlying cache")
        void updateCacheShouldCallRefresh() {
            // WHEN
            cacheManager.refreshCache();

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

    @Nested
    @DisplayName("Cache Initialization and Loading")
    class CacheInitializationAndLoading {

        @Test
        @DisplayName("getCache() should load metadata from XmlFile if it exists")
        void shouldLoadFromDiskOnInitialization() throws Exception {
            // GIVEN: A cache file exists
            List<BitwardenItemMetadata> diskMetadata = List.of(mock(BitwardenItemMetadata.class));

            try (MockedConstruction<XmlFile> xmlFileMock = mockConstruction(XmlFile.class, (mock, context) -> {
                when(mock.exists()).thenReturn(true);
                when(mock.read()).thenReturn(diskMetadata);
            })) {
                // WHEN: We force re-initialization *after* mocks are set
                forceCacheReinitialization();
                List<BitwardenItemMetadata> metadata = cacheManager.getMetadata(); // Triggers getCache()

                // THEN
                assertEquals(diskMetadata, metadata);
                assertEquals(1, xmlFileMock.constructed().size());
                verify(xmlFileMock.constructed().get(0)).exists();
                verify(xmlFileMock.constructed().get(0)).read();
                // Verify CLI was *not* called for the initial load
                mockedCli.verify(() -> BitwardenCli.listItemsMetadata(any()), never());
            }
        }

        @Test
        @DisplayName("getCache() should log warning on IOException")
        void shouldLogWarningOnIOException() throws Exception {
            // GIVEN
            try (MockedConstruction<XmlFile> xmlFileMock = mockConstruction(XmlFile.class, (mock, context) -> {
                when(mock.exists()).thenReturn(true);
                when(mock.read()).thenThrow(new IOException("Disk read error"));
            })) {
                // WHEN: We force re-initialization *after* mocks are set
                forceCacheReinitialization();
                List<BitwardenItemMetadata> metadata = cacheManager.getMetadata(); // Triggers getCache()

                // THEN
                assertTrue(metadata.isEmpty());
                verify(xmlFileMock.constructed().get(0)).read();
            }
        }

        @Test
        @DisplayName("getCache() should log warning on ClassCastException")
        void shouldLogWarningOnClassCastException() throws Exception {
            // GIVEN
            try (MockedConstruction<XmlFile> xmlFileMock = mockConstruction(XmlFile.class, (mock, context) -> {
                when(mock.exists()).thenReturn(true);
                when(mock.read()).thenReturn("a-string-not-a-list"); // Simulates corrupt file
            })) {
                // WHEN: We force re-initialization *after* mocks are set
                forceCacheReinitialization();
                List<BitwardenItemMetadata> metadata = cacheManager.getMetadata(); // Triggers getCache()

                // THEN
                assertTrue(metadata.isEmpty());
                verify(xmlFileMock.constructed().get(0)).read();
            }
        }

        @Test
        @DisplayName("fetchData() should be called by cache load and save to disk")
        void shouldCallFetchDataAndSaveToDisk() throws Exception {
            // GIVEN: No cache file exists, and CLI calls will succeed
            List<BitwardenItemMetadata> mockCliMetadata =
                    List.of(mock(BitwardenItemMetadata.class), mock(BitwardenItemMetadata.class));
            when(sessionManagerMock.getSessionKey()).thenReturn(mockSecret);
            mockedCli.when(() -> BitwardenCli.listItemsMetadata(mockSecret)).thenReturn(mockCliMetadata);

            try (MockedConstruction<XmlFile> xmlFileMock = mockConstruction(
                    XmlFile.class, (mock, context) -> when(mock.exists()).thenReturn(false))) {
                // WHEN
                forceCacheReinitialization();
                List<BitwardenItemMetadata> metadata = cacheManager.getMetadata();
                assertTrue(metadata.isEmpty(), "First call should be empty");

                ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
                verify(executorMock, atLeast(1)).submit(taskCaptor.capture());
                assertDoesNotThrow(() -> taskCaptor.getValue().run());

                // THEN
                InOrder inOrder = inOrder(sessionManagerMock);
                inOrder.verify(sessionManagerMock, times(2)).getSessionKey(); // One for sync, one for list

                mockedCli.verify(() -> BitwardenCli.sync(mockSecret));
                mockedCli.verify(() -> BitwardenCli.listItemsMetadata(mockSecret));
                verify(xmlFileMock.constructed().get(1)).write(mockCliMetadata);

                List<BitwardenItemMetadata> secondCallMetadata = cacheManager.getMetadata();
                assertEquals(mockCliMetadata, secondCallMetadata);
            }
        }

        @Test
        @DisplayName("fetchData() should log warning on disk write failure")
        void fetchDataShouldLogWarningOnDiskWriteFailure() throws Exception {
            // GIVEN: No cache file exists, CLI calls succeed
            List<BitwardenItemMetadata> mockCliMetadata = List.of(mock(BitwardenItemMetadata.class));
            when(sessionManagerMock.getSessionKey()).thenReturn(mockSecret);
            mockedCli.when(() -> BitwardenCli.listItemsMetadata(mockSecret)).thenReturn(mockCliMetadata);

            try (MockedConstruction<XmlFile> xmlFileMock = mockConstruction(XmlFile.class, (mock, context) -> {
                when(mock.exists()).thenReturn(false);
                if (context.getCount() > 1) {
                    doThrow(new IOException("Disk write error")).when(mock).write(any());
                }
            })) {
                // WHEN
                forceCacheReinitialization();
                cacheManager.getMetadata(); // Triggers getCache()

                ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
                verify(executorMock, atLeast(1)).submit(taskCaptor.capture());
                assertDoesNotThrow(() -> taskCaptor.getValue().run());

                // THEN
                mockedCli.verify(() -> BitwardenCli.sync(mockSecret));
                mockedCli.verify(() -> BitwardenCli.listItemsMetadata(mockSecret));
                verify(xmlFileMock.constructed().get(1)).write(mockCliMetadata);
            }
        }

        @Test
        @DisplayName("updateCache() should trigger fetchData() via reload")
        void updateCacheShouldTriggerFetchData() throws Exception {
            forceCacheReinitialization();
            cacheManager.getMetadata(); // This populates the cache

            ArgumentCaptor<Runnable> initialTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(executorMock).submit(initialTaskCaptor.capture());
            initialTaskCaptor.getValue().run();
            clearInvocations(sessionManagerMock);
            clearInvocations(executorMock); // Clear executor mocks too
            mockedCli.clearInvocations();

            List<BitwardenItemMetadata> freshMetadata = List.of(mock(BitwardenItemMetadata.class));
            when(sessionManagerMock.getSessionKey()).thenReturn(mockSecret);
            mockedCli.when(() -> BitwardenCli.listItemsMetadata(mockSecret)).thenReturn(freshMetadata);

            // WHEN
            cacheManager.refreshCache(); // This calls cache.refresh(), which submits fetchData to the executor

            // THEN
            ArgumentCaptor<Runnable> reloadTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
            // The ListeningDecorator calls 'execute', not 'submit', for the reload task
            verify(executorMock, times(1)).execute(reloadTaskCaptor.capture());

            // Run the captured task, which is the one submitted by reload()
            reloadTaskCaptor.getValue().run();

            verify(sessionManagerMock, times(2)).getSessionKey(); // one for sync, one for list
            mockedCli.verify(() -> BitwardenCli.sync(mockSecret));
            mockedCli.verify(() -> BitwardenCli.listItemsMetadata(mockSecret));
        }
    }
}
