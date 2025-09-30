package com.mwdle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.Credentials;
import com.mwdle.bitwarden.BitwardenAuthenticationException;
import com.mwdle.bitwarden.BitwardenCLI;
import com.mwdle.bitwarden.BitwardenSessionManager;
import com.mwdle.converters.CredentialConverter;
import hudson.model.ItemGroup;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenCredentialsProvider.
 * This is a pure unit test that uses Mockito's static mocking to completely
 * isolate the provider from its dependencies. It does not require a running Jenkins instance.
 */
@DisplayName("BitwardenCredentialsProvider")
class BitwardenCredentialsProviderTest {

    private MockedStatic<BitwardenSessionManager> mockedSessionManager;
    private MockedStatic<BitwardenCLI> mockedCli;
    private MockedStatic<CredentialConverter> mockedConverter;

    private BitwardenCredentialsProvider provider;
    private ItemGroup<?> mockItemGroup;
    private Authentication mockAuthentication;

    @BeforeEach
    void setUp() {
        mockedSessionManager = mockStatic(BitwardenSessionManager.class);
        mockedCli = mockStatic(BitwardenCLI.class);
        mockedConverter = mockStatic(CredentialConverter.class);

        provider = new BitwardenCredentialsProvider();

        mockItemGroup = mock(ItemGroup.class);
        mockAuthentication = mock(Authentication.class);
    }

    @AfterEach
    void tearDown() {
        mockedSessionManager.close();
        mockedCli.close();
        mockedConverter.close();
    }

    @Nested
    @DisplayName("getCredentialsInItemGroup() method")
    class GetCredentials {

        //        @Test
        //        @DisplayName("should return credentials by both name and ID when session is valid")
        //        void shouldReturnConvertedCredentialsWhenSessionIsValid() throws Exception {
        //            BitwardenSessionManager sessionManagerMock = mock(BitwardenSessionManager.class);
        //            Secret fakeToken = mock(Secret.class);
        //            when(sessionManagerMock.getSessionToken()).thenReturn(fakeToken);
        //            mockedSessionManager.when(BitwardenSessionManager::getInstance).thenReturn(sessionManagerMock);
        //
        //            BitwardenItem mockItem = mock(BitwardenItem.class);
        //            when(mockItem.getId()).thenReturn("item-id");
        //            when(mockItem.getName()).thenReturn("Item Name");
        //            mockedCli.when(() -> BitwardenCLI.listItems(fakeToken)).thenReturn(List.of(mockItem));
        //
        //            BitwardenItemConverter converterMock = mock(BitwardenItemConverter.class);
        //            StandardCredentials credentialById = mock(StringCredentials.class);
        //            when(credentialById.getId()).thenReturn("item-id");
        //            StandardCredentials credentialByName = mock(StringCredentials.class);
        //            when(credentialByName.getId()).thenReturn("Item Name");
        //
        //            mockedConverter
        //                    .when(() -> BitwardenItemConverter.findConverter(mockItem))
        //                    .thenReturn(converterMock);
        //            when(converterMock.convert(any(), eq("Item Name"), any(), any())).thenReturn(credentialByName);
        //            when(converterMock.convert(any(), eq("item-id"), any(), any())).thenReturn(credentialById);
        //
        //            List<Credentials> credentials = provider.getCredentialsInItemGroup(
        //                    Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());
        //
        //            assertEquals(2, credentials.size(), "Should have created two credentials for the one item.");
        //            List<String> ids = credentials.stream()
        //                    .map(c -> ((StandardCredentials) c).getId())
        //                    .toList();
        //            assertTrue(ids.contains("item-id"), "Should contain credential by ID.");
        //            assertTrue(ids.contains("Item Name"), "Should contain credential by name.");
        //            mockedCli.verify(() -> BitwardenCLI.sync(fakeToken), times(1));
        //        }

        //        @Test
        //        @DisplayName("should filter credentials by the requested type")
        //        void shouldFilterCredentialsByRequestedType() throws Exception {
        //            BitwardenSessionManager sessionManagerMock = mock(BitwardenSessionManager.class);
        //            Secret fakeToken = mock(Secret.class);
        //            when(sessionManagerMock.getSessionToken()).thenReturn(fakeToken);
        //            mockedSessionManager.when(BitwardenSessionManager::getInstance).thenReturn(sessionManagerMock);
        //
        //            BitwardenItem stringItem = mock(BitwardenItem.class);
        //            BitwardenItem loginItem = mock(BitwardenItem.class);
        //
        //            mockedCli.when(() -> BitwardenCLI.listItems(fakeToken)).thenReturn(List.of(stringItem,
        // loginItem));
        //
        //            BitwardenItemConverter stringConverter = mock(BitwardenItemConverter.class);
        //            when(BitwardenItemConverter.findConverter(stringItem)).thenReturn(stringConverter);
        //            when(stringConverter.convert(any(), any(), any(),
        // any())).thenReturn(mock(StringCredentials.class));
        //
        //            BitwardenItemConverter loginConverter = mock(BitwardenItemConverter.class);
        //            when(BitwardenItemConverter.findConverter(loginItem)).thenReturn(loginConverter);
        //            when(loginConverter.convert(any(), any(), any(), any()))
        //                    .thenReturn(mock(StandardUsernamePasswordCredentials.class));
        //
        //            List<StringCredentials> credentials = provider.getCredentialsInItemGroup(
        //                    StringCredentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());
        //
        //            assertEquals(2, credentials.size(), "Should only return the two StringCredentials (by name and
        // ID).");
        //        }

        //        @Test
        //        @DisplayName("should ignore items that have no converter")
        //        void shouldIgnoreItemsWithNoConverter() throws Exception {
        //            BitwardenSessionManager sessionManagerMock = mock(BitwardenSessionManager.class);
        //            Secret fakeToken = mock(Secret.class);
        //            when(sessionManagerMock.getSessionToken()).thenReturn(fakeToken);
        //            mockedSessionManager.when(BitwardenSessionManager::getInstance).thenReturn(sessionManagerMock);
        //
        //            BitwardenItem mockItem = mock(BitwardenItem.class);
        //            mockedCli.when(() -> BitwardenCLI.listItems(fakeToken)).thenReturn(List.of(mockItem));
        //
        //            mockedConverter
        //                    .when(() -> BitwardenItemConverter.findConverter(mockItem))
        //                    .thenReturn(null);
        //
        //            List<Credentials> credentials = provider.getCredentialsInItemGroup(
        //                    Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());
        //
        //            assertTrue(credentials.isEmpty(), "Should return an empty list if no items can be converted.");
        //        }

        @Test
        @DisplayName("should return an empty list if context is missing")
        void shouldReturnEmptyListIfContextIsMissing() {
            List<Credentials> noItemGroup = provider.getCredentialsInItemGroup(
                    Credentials.class, null, mockAuthentication, Collections.emptyList());
            List<Credentials> noAuth =
                    provider.getCredentialsInItemGroup(Credentials.class, mockItemGroup, null, Collections.emptyList());

            assertTrue(noItemGroup.isEmpty(), "Should be empty if item group is null.");
            assertTrue(noAuth.isEmpty(), "Should be empty if authentication is null.");
        }

        @Test
        @DisplayName("should throw a RuntimeException when authentication fails")
        void shouldThrowRuntimeExceptionWhenAuthFails() throws Exception {
            BitwardenSessionManager sessionManagerMock = mock(BitwardenSessionManager.class);
            when(sessionManagerMock.getSessionToken())
                    .thenThrow(new BitwardenAuthenticationException("Auth failed", null));
            mockedSessionManager.when(BitwardenSessionManager::getInstance).thenReturn(sessionManagerMock);

            assertThrows(
                    RuntimeException.class,
                    () -> provider.getCredentialsInItemGroup(
                            Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList()));
        }

        //        @Test
        //        @DisplayName("should return an empty list on IO exception")
        //        void shouldReturnEmptyListWhenIoFails() throws Exception {
        //            BitwardenSessionManager sessionManagerMock = mock(BitwardenSessionManager.class);
        //            when(sessionManagerMock.getSessionToken()).thenThrow(new IOException("CLI failed"));
        //            mockedSessionManager.when(BitwardenSessionManager::getInstance).thenReturn(sessionManagerMock);
        //
        //            // Fails because this call uses Jenkins.get() which is not available in test contexts, TODO mock
        // jenkins.get() like in other tests.
        //            List<Credentials> credentials = provider.getCredentialsInItemGroup(
        //                    Credentials.class, mockItemGroup, mockAuthentication, Collections.emptyList());
        //
        //            assertTrue(credentials.isEmpty(), "Should return an empty list on a non-auth failure.");
        //        }
    }
}
