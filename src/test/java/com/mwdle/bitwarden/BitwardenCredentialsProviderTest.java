package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.converters.CredentialConverter;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.model.ItemGroup;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenCredentialsProvider.
 * This is a pure unit test that uses Mockito's static mocking to completely
 * isolate the provider from its dependencies. It does not require a running Jenkins instance.
 */
@DisplayName("BitwardenCredentialsProvider")
class BitwardenCredentialsProviderTest {

    private MockedStatic<BitwardenConfig> mockedConfig;
    private MockedStatic<CacheManager> mockedCacheManager;
    private MockedStatic<CredentialConverter> mockedConverter;
    private MockedStatic<Jenkins> mockedJenkins;

    @Mock
    private BitwardenConfig configMock;

    @Mock
    private CacheManager cacheManagerMock;

    @Mock
    private ItemGroup<?> mockItemGroup;

    @Mock
    private Authentication mockAuthentication;

    private BitwardenCredentialsProvider provider;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        mockedConfig = mockStatic(BitwardenConfig.class);
        mockedCacheManager = mockStatic(CacheManager.class);
        mockedConverter = mockStatic(CredentialConverter.class);
        mockedJenkins = mockStatic(Jenkins.class);

        when(BitwardenConfig.getInstance()).thenReturn(configMock);
        when(CacheManager.getInstance()).thenReturn(cacheManagerMock);

        provider = new BitwardenCredentialsProvider();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedConfig.close();
        mockedCacheManager.close();
        mockedConverter.close();
        mockedJenkins.close();
        closeable.close();
    }

    private BitwardenItemMetadata createMockMetadata(String id, String name) {
        BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
        when(metadata.getId()).thenReturn(id);
        when(metadata.getName()).thenReturn(name);
        return metadata;
    }

    @Nested
    @DisplayName("listCredentials() method")
    class ListCredentials {

        @Test
        @DisplayName("should return empty list when plugin is not configured")
        void shouldReturnEmptyListWhenNotConfigured() {
            when(configMock.isConfigured()).thenReturn(false);
            assertTrue(provider.listCredentials().isEmpty());
        }

        @Test
        @DisplayName("should return empty list when cache is empty")
        void shouldReturnEmptyListWhenCacheIsEmpty() {
            when(configMock.isConfigured()).thenReturn(true);
            when(cacheManagerMock.getMetadata()).thenReturn(Collections.emptyList());
            assertTrue(provider.listCredentials().isEmpty());
        }

        @Test
        @DisplayName("should use name as ID for items with unique names")
        void shouldUseNameAsIdForUniqueItems() {
            // GIVEN
            when(configMock.isConfigured()).thenReturn(true);
            BitwardenItemMetadata uniqueItem = createMockMetadata("uuid-1", "UniqueName");
            when(cacheManagerMock.getMetadata()).thenReturn(List.of(uniqueItem));

            CredentialConverter converterMock = mock(CredentialConverter.class);
            mockedConverter
                    .when(() -> CredentialConverter.findConverter(uniqueItem))
                    .thenReturn(converterMock);
            when(converterMock.createProxy(any(), anyString(), any())).thenReturn(mock(StandardCredentials.class));

            // WHEN
            provider.listCredentials();

            // THEN
            verify(converterMock).createProxy(any(), eq("UniqueName"), eq(uniqueItem));
        }

        @Test
        @DisplayName("should use UUID as ID for items with duplicate names")
        void shouldUseUuidAsIdForDuplicateNames() {
            // GIVEN
            when(configMock.isConfigured()).thenReturn(true);
            BitwardenItemMetadata item1 = createMockMetadata("uuid-1", "DuplicateName");
            BitwardenItemMetadata item2 = createMockMetadata("uuid-2", "DuplicateName");
            when(cacheManagerMock.getMetadata()).thenReturn(List.of(item1, item2));

            CredentialConverter converterMock = mock(CredentialConverter.class);
            mockedConverter
                    .when(() -> CredentialConverter.findConverter(any(BitwardenItemMetadata.class)))
                    .thenReturn(converterMock);
            when(converterMock.createProxy(any(), anyString(), any())).thenReturn(mock(StandardCredentials.class));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);

            // WHEN
            List<Credentials> result = provider.listCredentials();

            // THEN
            assertEquals(2, result.size());
            verify(converterMock, times(2)).createProxy(any(), idCaptor.capture(), any());
            List<String> capturedIds = idCaptor.getAllValues();
            assertTrue(capturedIds.contains("uuid-1"));
            assertTrue(capturedIds.contains("uuid-2"));
        }

        @Test
        @DisplayName("should correctly handle a mix of unique and duplicate names")
        void shouldHandleMixedNames() {
            // GIVEN
            when(configMock.isConfigured()).thenReturn(true);
            BitwardenItemMetadata item1 = createMockMetadata("uuid-1", "DuplicateName");
            BitwardenItemMetadata item2 = createMockMetadata("uuid-2", "DuplicateName");
            BitwardenItemMetadata item3 = createMockMetadata("uuid-3", "UniqueName");
            when(cacheManagerMock.getMetadata()).thenReturn(List.of(item1, item2, item3));

            CredentialConverter converterMock = mock(CredentialConverter.class);
            mockedConverter
                    .when(() -> CredentialConverter.findConverter(any(BitwardenItemMetadata.class)))
                    .thenReturn(converterMock);
            when(converterMock.createProxy(any(), anyString(), any())).thenReturn(mock(StandardCredentials.class));

            // WHEN
            List<Credentials> result = provider.listCredentials();

            // THEN
            assertEquals(3, result.size());

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(converterMock, times(3)).createProxy(any(), idCaptor.capture(), any());
            List<String> capturedIds = idCaptor.getAllValues();

            assertTrue(capturedIds.contains("uuid-1"));
            assertTrue(capturedIds.contains("uuid-2"));
            assertTrue(capturedIds.contains("UniqueName"));
        }

        @Test
        @DisplayName("should ignore items for which no converter is found")
        void shouldIgnoreItemsWithNoConverter() {
            // GIVEN
            when(configMock.isConfigured()).thenReturn(true);
            BitwardenItemMetadata convertibleItem = createMockMetadata("uuid-1", "Convertible");
            BitwardenItemMetadata ignoredItem = createMockMetadata("uuid-2", "Ignored");
            when(cacheManagerMock.getMetadata()).thenReturn(List.of(convertibleItem, ignoredItem));

            CredentialConverter converterMock = mock(CredentialConverter.class);
            mockedConverter
                    .when(() -> CredentialConverter.findConverter(convertibleItem))
                    .thenReturn(converterMock);
            mockedConverter
                    .when(() -> CredentialConverter.findConverter(ignoredItem))
                    .thenReturn(null); // No
            // converter

            when(converterMock.createProxy(any(), anyString(), any())).thenReturn(mock(StandardCredentials.class));

            // WHEN
            List<Credentials> result = provider.listCredentials();

            // THEN
            assertEquals(1, result.size());
            verify(converterMock, times(1)).createProxy(any(), eq("Convertible"), any());
        }
    }

    @Nested
    @DisplayName("getCredentialsInItemGroup() method")
    class GetCredentialsInItemGroup {
        @Test
        @DisplayName("should return an empty list if context is missing")
        void shouldReturnEmptyListIfContextIsMissing() {
            // WHEN
            List<Credentials> noItemGroup = provider.getCredentialsInItemGroup(
                    Credentials.class, null, mockAuthentication, Collections.emptyList());
            List<Credentials> noAuth =
                    provider.getCredentialsInItemGroup(Credentials.class, mockItemGroup, null, Collections.emptyList());

            // THEN
            assertTrue(noItemGroup.isEmpty());
            assertTrue(noAuth.isEmpty());
        }

        @Test
        @DisplayName("should return an empty list if not configured")
        void shouldReturnEmptyListWhenNotConfigured() {
            // GIVEN
            BitwardenCredentialsProvider providerSpy = spy(provider);
            doReturn(Collections.emptyList()).when(providerSpy).listCredentials();

            // WHEN
            List<Credentials> result = providerSpy.getCredentialsInItemGroup(
                    Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());

            // THEN
            assertTrue(result.isEmpty());
            verify(providerSpy, times(1)).listCredentials();
        }

        @Test
        @DisplayName("should filter credentials by the requested type")
        void shouldFilterCredentialsByRequestedType() {
            // GIVEN: We spy on the provider to mock the result of listCredentials()
            BitwardenCredentialsProvider providerSpy = spy(provider);

            Credentials stringCred = mock(StringCredentials.class);
            Credentials userPassCred =
                    mock(com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class);
            List<Credentials> allCredentials = List.of(stringCred, userPassCred);
            doReturn(allCredentials).when(providerSpy).listCredentials();

            // WHEN
            List<StringCredentials> stringResult = providerSpy.getCredentialsInItemGroup(
                    StringCredentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());

            List<Credentials> allResult = providerSpy.getCredentialsInItemGroup(
                    Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());

            // THEN
            assertEquals(1, stringResult.size());
            assertTrue(stringResult.contains(stringCred));

            assertEquals(2, allResult.size());
        }
    }

    @Nested
    @DisplayName("Other public methods")
    class OtherPublicMethods {
        @Test
        @DisplayName("getStore() should return the store for the Jenkins context")
        void shouldReturnStoreForJenkinsContext() {
            Jenkins jenkinsMock = mock(Jenkins.class);
            assertNotNull(provider.getStore(jenkinsMock));
        }

        @Test
        @DisplayName("getStore() should return null for other contexts")
        void shouldReturnNullForOtherContexts() {
            ItemGroup<?> otherContext = mock(ItemGroup.class);
            assertNull(provider.getStore(otherContext));
        }

        @Test
        @DisplayName("getIconClassName() should return the correct class name")
        void shouldReturnCorrectIconClassName() {
            assertEquals("symbol-icon plugin-bitwarden-credentials-provider", provider.getIconClassName());
        }
    }
}
