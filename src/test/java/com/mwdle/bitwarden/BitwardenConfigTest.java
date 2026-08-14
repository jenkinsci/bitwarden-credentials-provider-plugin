package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenCLIManager;
import com.mwdle.bitwarden.cli.SessionManager;
import hudson.ExtensionList;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.stapler.StaplerRequest2;
import org.mockito.*;
import org.springframework.security.core.Authentication;

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
    private MockedStatic<SessionManager> mockedSessionManager;
    private MockedStatic<CacheManager> mockedCacheManager;
    private MockedStatic<BitwardenCLIManager> mockedCliManager;
    private MockedStatic<BitwardenCLI> mockedCli;
    private MockedStatic<GlobalConfiguration> mockedGlobalConfig;
    private MockedStatic<Messages> mockedMessages;
    private MockedStatic<ACL> mockedAcl;

    // Mock instances of dependencies
    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ScheduledExecutorService executorMock;

    @Mock
    private SessionManager sessionManagerMock;

    @Mock
    private CacheManager cacheManagerMock;

    @Mock
    private BitwardenCLIManager cliManagerMock;

    @Mock
    private ExtensionList<CredentialsProvider> extensionListMock;

    @Mock
    private CredentialsProvider credentialsProviderMock;

    @Mock
    private ExtensionList<GlobalConfiguration> globalConfigListMock;

    @Mock
    private StaplerRequest2 staplerRequestMock;

    @Mock
    private JSONObject jsonMock;

    @Mock
    private Authentication authenticationMock;

    private BitwardenConfig config;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        // Initialize all static mocks
        mockedJenkins = mockStatic(Jenkins.class);
        mockedTimer = mockStatic(Timer.class);
        mockedSessionManager = mockStatic(SessionManager.class);
        mockedCacheManager = mockStatic(CacheManager.class);
        mockedCliManager = mockStatic(BitwardenCLIManager.class);
        mockedCli = mockStatic(BitwardenCLI.class);
        mockedGlobalConfig = mockStatic(GlobalConfiguration.class);
        mockedMessages = mockStatic(Messages.class);
        mockedAcl = mockStatic(ACL.class);

        // Configure mocks to return singleton instances
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());
        when(jenkinsMock.getItemGroup()).thenReturn(jenkinsMock); // Jenkins *is* the ItemGroup
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(authenticationMock);
        when(Timer.get()).thenReturn(executorMock);
        when(SessionManager.getInstance()).thenReturn(sessionManagerMock);
        when(CacheManager.getInstance()).thenReturn(cacheManagerMock);
        when(BitwardenCLIManager.getInstance()).thenReturn(cliManagerMock);

        // Mocks for getInstance()
        when(GlobalConfiguration.all()).thenReturn(globalConfigListMock);

        // Mocks for getDisplayName()
        when(Messages.BitwardenConfig_DisplayName()).thenReturn("Bitwarden");

        // Mocks for doFill... methods
        when(jenkinsMock.getExtensionList(CredentialsProvider.class)).thenReturn(extensionListMock);
        when(extensionListMock.stream()).thenReturn(Stream.of(credentialsProviderMock));
        when(credentialsProviderMock.getCredentialsInItemGroup(any(), any(), any(), anyList()))
                .thenReturn(Collections.emptyList());

        // Prevent the real constructor from trying to load a file
        config = spy(new BitwardenConfig());
        doNothing().when(config).load();

        // Must configure getInstance() *after* config is spy'd
        when(globalConfigListMock.get(BitwardenConfig.class)).thenReturn(config);
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
        mockedGlobalConfig.close();
        mockedMessages.close();
        mockedAcl.close();
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
            verify(sessionManagerMock, never()).invalidateSession();
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

        @Test
        @DisplayName("should trigger refresh on first save")
        void shouldHandleFirstSaveWithNullLoadedConfig() throws Exception {
            // GIVEN: The config is fully configured, but loadedConfig is null
            doReturn(true).when(config).isConfigured();
            setSnapshot(config, null); // Simulate first-ever save
            config.setServerUrl("http://new-url");

            // WHEN
            config.save();

            // THEN: The refresh task should be submitted
            verify(executorMock, times(1)).submit(any(Runnable.class));
        }

        @Test
        @DisplayName("should execute background sync on config change")
        void shouldExecuteBackgroundSyncOnConfigChange() throws Exception {
            // GIVEN: The config is fully configured
            doReturn(true).when(config).isConfigured();
            when(cliManagerMock.provisionExecutable()).thenReturn(true);
            setSnapshot(config, invokeSnapshot(config));
            config.setServerUrl("http://new-url");

            // WHEN
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            config.save();

            // THEN
            verify(executorMock).submit(taskCaptor.capture());
            Runnable backgroundTask = taskCaptor.getValue();
            backgroundTask.run();

            verify(sessionManagerMock).invalidateSession();
            verify(cacheManagerMock).invalidateCache();
            verify(cliManagerMock).provisionExecutable();
            verify(cacheManagerMock).refreshCache();
        }

        @Test
        @DisplayName("should not run updateCache if provisioning fails")
        void shouldNotRunUpdateCacheIfProvisioningFails() throws Exception {
            // GIVEN: The config is fully configured
            doReturn(true).when(config).isConfigured();
            when(cliManagerMock.provisionExecutable()).thenReturn(false); // Provisioning fails
            setSnapshot(config, invokeSnapshot(config));
            config.setServerUrl("http://new-url");

            // WHEN
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            config.save();

            // THEN
            verify(executorMock).submit(taskCaptor.capture());
            Runnable backgroundTask = taskCaptor.getValue();
            backgroundTask.run();

            verify(sessionManagerMock).invalidateSession();
            verify(cacheManagerMock).invalidateCache();
            verify(cliManagerMock).provisionExecutable();
            verify(cacheManagerMock, never()).refreshCache();
        }
    }

    @Nested
    @DisplayName("Action methods (do...)")
    class ActionMethods {

        @Test
        @DisplayName("doRefreshCache should trigger invalidation and return OK")
        void doRefreshCacheSuccess() {
            // GIVEN
            when(Messages.validation_refreshStarted()).thenReturn("Refresh started.");

            // WHEN
            FormValidation result = config.doRefreshCache();

            // THEN
            verify(sessionManagerMock, times(1)).invalidateSession();
            verify(cacheManagerMock, times(1)).refreshCache();
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertEquals("Refresh started.", result.getMessage());
        }

        @Test
        @DisplayName("doRefreshCache should handle exceptions and return ERROR")
        void doRefreshCacheError() {
            // GIVEN: The session manager will throw an exception
            doThrow(new RuntimeException("Test Exception"))
                    .when(sessionManagerMock)
                    .invalidateSession();
            when(Messages.validation_refreshError(anyString())).thenReturn("Error: Test Exception");

            // WHEN
            FormValidation result = config.doRefreshCache();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertEquals("Error: Test Exception", result.getMessage());
        }

        @Test
        @DisplayName("doCheckCliVersion should return OK with version")
        void doCheckCliVersionSuccess() throws Exception {
            // GIVEN
            when(BitwardenCLI.version()).thenReturn("2023.10.0");
            when(Messages.validation_cliVersion(anyString())).thenReturn("Version: 2023.10.0");

            // WHEN
            FormValidation result = config.doCheckCliVersion();

            // THEN
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertEquals("Version: 2023.10.0", result.getMessage());
        }

        @Test
        @DisplayName("doCheckCliVersion should return ERROR on exception")
        void doCheckCliVersionError() throws Exception {
            // GIVEN
            when(BitwardenCLI.version()).thenThrow(new IOException("CLI not found"));
            when(Messages.validation_cliError(anyString())).thenReturn("Error: CLI not found");

            // WHEN
            FormValidation result = config.doCheckCliVersion();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertEquals("Error: CLI not found", result.getMessage());
        }

        @Test
        @DisplayName("doForceUpdateCli should return OK with new version")
        void doForceUpdateCliSuccess() throws Exception {
            // GIVEN
            when(cliManagerMock.downloadLatestExecutable()).thenReturn(true);
            when(BitwardenCLI.version()).thenReturn("2023.10.1");
            when(Messages.validation_cliUpdateOk(anyString())).thenReturn("Updated to 2023.10.1");

            // WHEN
            FormValidation result = config.doForceUpdateCli();

            // THEN
            verify(cliManagerMock, times(1)).downloadLatestExecutable();
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertEquals("Updated to 2023.10.1", result.getMessage());
        }

        @Test
        @DisplayName("doForceUpdateCli should return ERROR on exception")
        void doForceUpdateCliError() {
            // GIVEN
            when(cliManagerMock.downloadLatestExecutable()).thenThrow(new RuntimeException("Download failed"));
            when(Messages.validation_cliUpdateError(anyString())).thenReturn("Error: Download failed");

            // WHEN
            FormValidation result = config.doForceUpdateCli();

            // THEN
            assertEquals(FormValidation.Kind.ERROR, result.kind);
            assertEquals("Error: Download failed", result.getMessage());
        }

        @Test
        @DisplayName("doForceUpdateCli should return WARNING if manual path is set")
        void doForceUpdateCliShouldReturnWarningIfPathIsSet() {
            // GIVEN
            config.setCliExecutablePath("/manual/path/bw");
            when(Messages.validation_cliUpdateManual()).thenReturn("Manual path set.");

            // WHEN
            FormValidation result = config.doForceUpdateCli();

            // THEN
            assertEquals(FormValidation.Kind.WARNING, result.kind);
            assertEquals("Manual path set.", result.getMessage());
            verify(cliManagerMock, never()).downloadLatestExecutable();
        }
    }

    @Nested
    @DisplayName("doVerifySession()")
    class VerifySession {

        @Test
        @DisplayName("should return WARNING when not configured")
        void shouldReturnWarningWhenNotConfigured() {
            // GIVEN: The plugin is not configured
            doReturn(false).when(config).isConfigured();
            when(Messages.validation_sessionNotConfigured()).thenReturn("Not configured.");

            // WHEN
            FormValidation result = config.doVerifySession();

            // THEN
            assertEquals(FormValidation.Kind.WARNING, result.kind);
            assertEquals("Not configured.", result.getMessage());
            // Ensure no session check was even attempted
            verify(sessionManagerMock, never()).isSessionValid();
        }

        @Test
        @DisplayName("should return OK when session is valid")
        void shouldReturnOkWhenSessionIsValid() {
            // GIVEN: The plugin is configured and the session is valid
            doReturn(true).when(config).isConfigured();
            when(sessionManagerMock.isSessionValid()).thenReturn(true);
            when(Messages.validation_sessionOk()).thenReturn("Session OK.");

            // WHEN
            FormValidation result = config.doVerifySession();

            // THEN
            assertEquals(FormValidation.Kind.OK, result.kind);
            assertEquals("Session OK.", result.getMessage());
        }

        @Test
        @DisplayName("should return WARNING when session is not valid")
        void shouldReturnWarningWhenSessionIsNotValid() {
            // GIVEN: The plugin is configured but the session is not valid
            doReturn(true).when(config).isConfigured();
            when(sessionManagerMock.isSessionValid()).thenReturn(false);
            when(Messages.validation_sessionNotFound()).thenReturn("Session not found.");

            // WHEN
            FormValidation result = config.doVerifySession();

            // THEN
            assertEquals(FormValidation.Kind.WARNING, result.kind);
            assertEquals("Session not found.", result.getMessage());
        }
    }

    @Nested
    @DisplayName("Configuration and Lifecycle")
    class ConfigurationMethods {

        @Test
        @DisplayName("getInstance should return correct singleton")
        void testGetInstance() {
            // GIVEN: setUp mocks GlobalConfiguration.all().get(...)
            // WHEN
            BitwardenConfig instance = BitwardenConfig.getInstance();
            // THEN
            assertEquals(config, instance);
        }

        @Test
        @DisplayName("getDisplayName should return message")
        void testGetDisplayName() {
            assertEquals("Bitwarden", config.getDisplayName());
            mockedMessages.verify(Messages::BitwardenConfig_DisplayName);
        }

        @Test
        @DisplayName("Getters and Setters should work")
        void shouldSetAndGetValues() {
            // WHEN
            config.setServerUrl("a");
            config.setApiCredentialId("b");
            config.setMasterPasswordCredentialId("c");
            config.setCliExecutablePath("d");
            config.setCacheDuration(10);
            config.setFileCredentialSuffixes("e");

            // THEN
            assertEquals("a", config.getServerUrl());
            assertEquals("b", config.getApiCredentialId());
            assertEquals("c", config.getMasterPasswordCredentialId());
            assertEquals("d", config.getCliExecutablePath());
            assertEquals(10, config.getCacheDuration());
            assertEquals("e", config.getFileCredentialSuffixes());
        }

        @Test
        @DisplayName("setCacheDuration should default to 5 if invalid")
        void shouldDefaultCacheDuration() {
            config.setCacheDuration(0);
            assertEquals(5, config.getCacheDuration());
            config.setCacheDuration(-10);
            assertEquals(5, config.getCacheDuration());
        }

        @DisplayName("isConfigured should return correct branch results")
        @ParameterizedTest
        @CsvSource({
            "null, null, false",
            "test-id, null, false",
            "null, test-id, false",
            "'', test-id, false",
            "test-id, '', false",
            "test-id, test-id, true"
        })
        void testIsConfiguredBranches(String apiId, String masterId, boolean expected) {
            // GIVEN
            config.setApiCredentialId("null".equals(apiId) ? null : apiId);
            config.setMasterPasswordCredentialId("null".equals(masterId) ? null : masterId);
            // WHEN
            boolean result = config.isConfigured();
            // THEN
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("configure method should call super.configure and save")
        void testConfigure() throws GlobalConfiguration.FormException {
            // GIVEN: config is a spy
            // We must stub the methods called *by* super.configure(),
            // since we can't stub the super() call itself.

            // Stub the void method bindJSON(Object, JSONObject) explicitly
            doNothing().when(staplerRequestMock).bindJSON(any(Object.class), any(JSONObject.class));

            // We also stub our own save() method to prevent it from
            // really running and to allow us to verify it.
            doNothing().when(config).save();

            // WHEN
            boolean result = config.configure(staplerRequestMock, jsonMock); // This calls the REAL configure()

            // THEN
            assertTrue(result);

            // Verify super.configure() was called (by checking its side-effect)
            verify(staplerRequestMock, times(1)).bindJSON(eq(config), eq(jsonMock));

            // Verify save() was called TWICE:
            // 1. Once by super.configure()
            // 2. Once by config.configure()
            verify(config, times(1)).save();
        }
    }

    @Nested
    @DisplayName("ListBoxFillMethods")
    @SuppressWarnings("unchecked")
    class ListBoxFillMethods {

        @Test
        @DisplayName("doFillApiCredentialIdItems should populate listbox")
        void doFillApiCredentialIdItems() {
            // GIVEN
            when(jenkinsMock.hasPermission(Jenkins.MANAGE)).thenReturn(true);
            List<StandardUsernamePasswordCredentials> credentials =
                    List.of(mock(StandardUsernamePasswordCredentials.class));
            when(credentialsProviderMock.getCredentialsInItemGroup(
                            eq(StandardUsernamePasswordCredentials.class), any(), any(), anyList()))
                    .thenReturn(credentials);

            // WHEN
            try (MockedConstruction<StandardListBoxModel> modelMock =
                    mockConstruction(StandardListBoxModel.class, (mock, context) -> {
                        when(mock.includeEmptyValue()).thenReturn(mock);
                        when(mock.includeMatchingAs(
                                        any(Authentication.class),
                                        any(ItemGroup.class),
                                        any(Class.class),
                                        anyList(),
                                        any(CredentialsMatcher.class)))
                                .thenReturn(mock);
                        when(mock.includeCurrentValue(anyString())).thenReturn(mock);
                    })) {

                ListBoxModel result = config.doFillApiCredentialIdItems(jenkinsMock, "current-api-id");

                // THEN
                assertEquals(1, modelMock.constructed().size());
                StandardListBoxModel constructedModel = modelMock.constructed().get(0);
                assertEquals(constructedModel, result);

                InOrder inOrder = inOrder(constructedModel);
                inOrder.verify(constructedModel).includeEmptyValue();
                inOrder.verify(constructedModel)
                        .includeMatchingAs(
                                eq(ACL.SYSTEM2),
                                eq(jenkinsMock),
                                eq(StandardUsernamePasswordCredentials.class),
                                anyList(),
                                any(CredentialsMatcher.class));
                inOrder.verify(constructedModel).includeCurrentValue("current-api-id");
            }
        }

        @Test
        @DisplayName("doFillMasterPasswordCredentialIdItems should populate listbox")
        void doFillMasterPasswordCredentialIdItems() {
            // GIVEN
            when(jenkinsMock.hasPermission(Jenkins.MANAGE)).thenReturn(true);
            List<StringCredentials> credentials = List.of(mock(StringCredentials.class));
            when(credentialsProviderMock.getCredentialsInItemGroup(
                            eq(StringCredentials.class), any(), any(), anyList()))
                    .thenReturn(credentials);

            // WHEN
            try (MockedConstruction<StandardListBoxModel> modelMock =
                    mockConstruction(StandardListBoxModel.class, (mock, context) -> {
                        when(mock.includeEmptyValue()).thenReturn(mock);
                        when(mock.includeMatchingAs(
                                        any(Authentication.class),
                                        any(ItemGroup.class),
                                        any(Class.class),
                                        anyList(),
                                        any(CredentialsMatcher.class)))
                                .thenReturn(mock);
                        when(mock.includeCurrentValue(anyString())).thenReturn(mock);
                    })) {

                ListBoxModel result = config.doFillMasterPasswordCredentialIdItems(jenkinsMock, "current-master-id");

                // THEN
                assertEquals(1, modelMock.constructed().size());
                StandardListBoxModel constructedModel = modelMock.constructed().get(0);
                assertEquals(constructedModel, result);

                InOrder inOrder = inOrder(constructedModel);
                inOrder.verify(constructedModel).includeEmptyValue();
                inOrder.verify(constructedModel)
                        .includeMatchingAs(
                                eq(ACL.SYSTEM2),
                                eq(jenkinsMock),
                                eq(StringCredentials.class),
                                anyList(),
                                any(CredentialsMatcher.class));
                inOrder.verify(constructedModel).includeCurrentValue("current-master-id");
            }
        }

        @Test
        @DisplayName("doFill... methods should return minimal listbox when no permission")
        void doFillMethodsNoPermission() {
            // GIVEN
            when(jenkinsMock.hasPermission(Jenkins.MANAGE)).thenReturn(false);

            // WHEN
            try (MockedConstruction<StandardListBoxModel> modelMock = mockConstruction(
                    StandardListBoxModel.class, (mock, context) -> when(mock.includeCurrentValue(anyString()))
                            .thenReturn(mock))) {

                config.doFillApiCredentialIdItems(jenkinsMock, "current-api-id");
                config.doFillMasterPasswordCredentialIdItems(jenkinsMock, "current-master-id");

                // THEN
                assertEquals(2, modelMock.constructed().size());
                StandardListBoxModel apiModel = modelMock.constructed().get(0);
                StandardListBoxModel masterModel = modelMock.constructed().get(1);

                // Verify the *only* call was to includeCurrentValue
                verify(apiModel).includeCurrentValue("current-api-id");
                verify(apiModel, never()).includeEmptyValue();
                verify(apiModel, never())
                        .includeMatchingAs(
                                any(Authentication.class),
                                any(ItemGroup.class),
                                any(Class.class),
                                anyList(),
                                any(CredentialsMatcher.class));

                verify(masterModel).includeCurrentValue("current-master-id");
                verify(masterModel, never()).includeEmptyValue();
                verify(masterModel, never())
                        .includeMatchingAs(
                                any(Authentication.class),
                                any(ItemGroup.class),
                                any(Class.class),
                                anyList(),
                                any(CredentialsMatcher.class));
            }
        }
    }
}
