package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenCLIManager;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import hudson.util.FormValidation;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the BitwardenConfig class.
 * <p>
 * This is a pure unit test that uses Mockito's static mocking to completely
 * isolate the configuration logic from the Jenkins runtime and other plugin components.
 * It verifies the save logic and all UI action methods.
 */
@DisplayName("BitwardenConfig")
class BitwardenConfigTest {

    @TempDir
    Path tempDir;

    // Mocks for static Jenkins and plugin classes
    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<Timer> mockedTimer;
    private MockedStatic<BitwardenSessionManager> mockedSessionManager;
    private MockedStatic<BitwardenCacheManager> mockedCacheManager;
    private MockedStatic<BitwardenCLIManager> mockedCliManager;
    private MockedStatic<BitwardenCLI> mockedCli;

    // Mock instances of dependencies
    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ScheduledExecutorService executorMock;

    @Mock
    private BitwardenSessionManager sessionManagerMock;

    @Mock
    private BitwardenCacheManager cacheManagerMock;

    @Mock
    private BitwardenCLIManager cliManagerMock;

    private BitwardenConfig config;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        // Initialize all static mocks
        mockedJenkins = mockStatic(Jenkins.class);
        mockedTimer = mockStatic(Timer.class);
        mockedSessionManager = mockStatic(BitwardenSessionManager.class);
        mockedCacheManager = mockStatic(BitwardenCacheManager.class);
        mockedCliManager = mockStatic(BitwardenCLIManager.class);
        mockedCli = mockStatic(BitwardenCLI.class);

        // Configure mocks to return singleton instances
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());
        when(Timer.get()).thenReturn(executorMock);
        when(BitwardenSessionManager.getInstance()).thenReturn(sessionManagerMock);
        when(BitwardenCacheManager.getInstance()).thenReturn(cacheManagerMock);
        when(BitwardenCLIManager.getInstance()).thenReturn(cliManagerMock);

        // Prevent the real constructor from trying to load a file
        config = spy(new BitwardenConfig());
        doNothing().when(config).load();
    }

    @AfterEach
    void tearDown() {
        // Close all static mocks to prevent test pollution
        mockedJenkins.close();
        mockedTimer.close();
        mockedSessionManager.close();
        mockedCacheManager.close();
        mockedCliManager.close();
        mockedCli.close();
        try {
            closeable.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to close Mockito mocks", e);
        }
    }

    @Nested
    @DisplayName("save() logic")
    class SaveLogic {

        private void setSnapshot(BitwardenConfig config, BitwardenConfig snapshot)
                throws NoSuchFieldException, IllegalAccessException {
            Field loadedConfigField = BitwardenConfig.class.getDeclaredField("loadedConfig");
            loadedConfigField.setAccessible(true);
            loadedConfigField.set(config, snapshot);
        }

        private BitwardenConfig invokeSnapshot(BitwardenConfig config) throws Exception {
            Method snapshotMethod = BitwardenConfig.class.getDeclaredMethod("snapshot");
            snapshotMethod.setAccessible(true);
            return (BitwardenConfig) snapshotMethod.invoke(config);
        }

        @Test
        @DisplayName("should not trigger refresh when config is unchanged")
        void shouldNotTriggerRefreshWhenUnchanged() throws Exception {
            // GIVEN: an initial configuration state
            config.setServerUrl("http://localhost");
            config.setApiCredentialId("api-id");
            config.setMasterPasswordCredentialId("master-id");
            // and we simulate a previous save by setting the snapshot
            setSnapshot(config, invokeSnapshot(config));

            // WHEN
            config.save();

            // THEN: No refresh logic should be triggered
            verify(executorMock, never()).submit(any(Runnable.class));
            verify(sessionManagerMock, never()).invalidateSessionToken();
            verify(cacheManagerMock, never()).invalidateCache();
        }

        @Test
        @DisplayName("should not trigger refresh when not configured")
        void shouldNotTriggerRefreshWhenNotConfigured() throws Exception {
            // GIVEN: The config is not fully configured
            doReturn(false).when(config).isConfigured();
            // and we simulate a previous save
            setSnapshot(config, invokeSnapshot(config));
            // and a critical setting is changed
            config.setServerUrl("http://new-url");

            // WHEN
            config.save();

            // THEN: No refresh logic should be triggered
            verify(executorMock, never()).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should not trigger refresh for non-critical changes")
        void shouldNotTriggerRefreshForNonCriticalChange() throws Exception {
            // GIVEN: The config is fully configured
            config.setApiCredentialId("api-id");
            config.setMasterPasswordCredentialId("master-id");
            doReturn(true).when(config).isConfigured();
            // and we simulate a previous save
            setSnapshot(config, invokeSnapshot(config));
            // and a non-critical setting is changed
            config.setCacheDuration(99);

            // WHEN
            config.save();

            // THEN: No refresh logic should be triggered
            verify(executorMock, never()).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should trigger refresh when serverUrl changes")
        void shouldTriggerRefreshOnServerUrlChange() throws Exception {
            // GIVEN: The config is fully configured
            doReturn(true).when(config).isConfigured();
            // and we simulate a previous save
            setSnapshot(config, invokeSnapshot(config));
            // and a critical setting is changed
            config.setServerUrl("http://new-url");

            // WHEN
            config.save();

            // THEN: The refresh task should be submitted
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should trigger refresh when apiCredentialId changes")
        void shouldTriggerRefreshOnApiCredentialIdChange() throws Exception {
            // GIVEN: The config is fully configured
            doReturn(true).when(config).isConfigured();
            // and we simulate a previous save
            setSnapshot(config, invokeSnapshot(config));
            // and a critical setting is changed
            config.setApiCredentialId("new-api-id");

            // WHEN
            config.save();

            // THEN: The refresh task should be submitted
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should trigger refresh when masterPasswordCredentialId changes")
        void shouldTriggerRefreshOnMasterPasswordCredentialIdChange() throws Exception {
            // GIVEN: The config is fully configured
            doReturn(true).when(config).isConfigured();
            // and we simulate a previous save
            setSnapshot(config, invokeSnapshot(config));
            // and a critical setting is changed
            config.setMasterPasswordCredentialId("new-master-id");

            // WHEN
            config.save();

            // THEN: The refresh task should be submitted
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }
    }

    @Nested
    @DisplayName("Action methods (do...)")
    class ActionMethods {

        @Test
        @DisplayName("doRefreshCache should trigger invalidation and return OK")
        void doRefreshCacheSuccess() {
            // WHEN
            FormValidation result = config.doRefreshCache();

            // THEN
            verify(sessionManagerMock, times(1)).invalidateSessionToken();
            verify(cacheManagerMock, times(1)).updateCache();
            assertEquals(FormValidation.Kind.OK, result.kind);
        }

        @Test
        @DisplayName("doRefreshCache should handle exceptions and return ERROR")
        void doRefreshCacheError() {
            // GIVEN: The session manager will throw an exception
            doThrow(new RuntimeException("Test Exception")).when(sessionManagerMock).invalidateSessionToken();

            // WHEN
            FormValidation result = config.doRefreshCache();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertTrue(result.getMessage().contains("Test Exception"));
        }

        @Test
        @DisplayName("doCheckCliVersion should return OK with version")
        void doCheckCliVersionSuccess() throws Exception {
            // GIVEN
            when(BitwardenCLI.version()).thenReturn("2023.10.0");

            // WHEN
            FormValidation result = config.doCheckCliVersion();

            // THEN
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertTrue(result.getMessage().contains("2023.10.0"));
        }

        @Test
        @DisplayName("doCheckCliVersion should return ERROR on exception")
        void doCheckCliVersionError() throws Exception {
            // GIVEN
            when(BitwardenCLI.version()).thenThrow(new IOException("CLI not found"));

            // WHEN
            FormValidation result = config.doCheckCliVersion();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertTrue(result.getMessage().contains("CLI not found"));
        }

        @Test
        @DisplayName("doForceUpdateCli should return OK with new version")
        void doForceUpdateCliSuccess() throws Exception {
            // GIVEN
            when(cliManagerMock.downloadLatestExecutable()).thenReturn(true);
            when(BitwardenCLI.version()).thenReturn("2023.10.1");

            // WHEN
            FormValidation result = config.doForceUpdateCli();

            // THEN
            verify(cliManagerMock, times(1)).downloadLatestExecutable();
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertTrue(result.getMessage().contains("2023.10.1"));
        }

        @Test
        @DisplayName("doForceUpdateCli should return ERROR on exception")
        void doForceUpdateCliError() {
            // GIVEN
            when(cliManagerMock.downloadLatestExecutable()).thenThrow(new RuntimeException("Download failed"));

            // WHEN
            FormValidation result = config.doForceUpdateCli();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertTrue(result.getMessage().contains("Download failed"));
        }
    }
}

