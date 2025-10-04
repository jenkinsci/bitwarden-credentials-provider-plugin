package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.security.ACL;
import hudson.security.Permission;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for the BitwardenCredentialsStore class.
 * <p>
 * This test suite verifies that the store correctly acts as a read-only view
 * on the {@link BitwardenCredentialsProvider}, respecting Jenkins permissions
 * and correctly disabling write operations.
 */
@DisplayName("BitwardenCredentialsStore")
class BitwardenCredentialsStoreTest {

    @Mock
    private BitwardenCredentialsProvider providerMock;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ACL aclMock;

    @Mock
    private Authentication authenticationMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<Messages> mockedMessages;
    private MockedStatic<Domain> mockedDomain;

    private BitwardenCredentialsStore store;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getACL()).thenReturn(aclMock);
        mockedJenkins.when(Jenkins::getAuthentication2).thenReturn(authenticationMock);

        mockedMessages = mockStatic(Messages.class);
        when(Messages.BitwardenCredentialsStore_DisplayName()).thenReturn("Bitwarden");

        // Mock the Domain.global() static method
        mockedDomain = mockStatic(Domain.class);

        store = new BitwardenCredentialsStore(providerMock);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedMessages.close();
        mockedDomain.close();
        closeable.close();
    }

    @Nested
    @DisplayName("getCredentials() method")
    class GetCredentials {

        @Test
        @DisplayName("should return empty list if user lacks VIEW permission")
        void shouldReturnEmptyListWhenNoPermission() {
            // GIVEN: The user does not have the VIEW permission
            when(aclMock.hasPermission2(authenticationMock, CredentialsProvider.VIEW))
                    .thenReturn(false);
            mockedDomain.when(Domain::global).thenReturn(mock(Domain.class));

            // WHEN
            List<Credentials> result = store.getCredentials(Domain.global());

            // THEN
            assertTrue(result.isEmpty());
            verify(providerMock, never()).listCredentials();
        }

        @Test
        @DisplayName("should return empty list for non-global domains")
        void shouldReturnEmptyListForNonGlobalDomain() {
            // GIVEN: The user has permission, but the domain is not global
            when(aclMock.hasPermission2(authenticationMock, CredentialsProvider.VIEW))
                    .thenReturn(true);
            Domain globalDomain = mock(Domain.class);
            Domain nonGlobalDomain = mock(Domain.class);
            mockedDomain.when(Domain::global).thenReturn(globalDomain);

            // WHEN
            List<Credentials> result = store.getCredentials(nonGlobalDomain);

            // THEN
            assertTrue(result.isEmpty());
            verify(providerMock, never()).listCredentials();
        }

        @Test
        @DisplayName("should delegate to provider when permissions and domain are correct")
        void shouldDelegateToProviderOnSuccess() {
            // GIVEN: The user has permission and the domain is global
            Domain globalDomain = mock(Domain.class);
            mockedDomain.when(Domain::global).thenReturn(globalDomain);
            when(aclMock.hasPermission2(authenticationMock, CredentialsProvider.VIEW))
                    .thenReturn(true);
            List<Credentials> expectedCredentials = List.of(mock(Credentials.class));
            when(providerMock.listCredentials()).thenReturn(expectedCredentials);

            // WHEN
            List<Credentials> result = store.getCredentials(globalDomain);

            // THEN
            assertEquals(expectedCredentials, result);
            verify(providerMock, times(1)).listCredentials();
        }
    }

    @Nested
    @DisplayName("Unsupported Write Operations")
    class UnsupportedOperations {

        @Test
        @DisplayName("should always return false")
        void shouldAlwaysReturnFalse() {
            mockedDomain.when(Domain::global).thenReturn(mock(Domain.class));
            assertFalse(store.addCredentials(Domain.global(), mock(Credentials.class)));
            assertFalse(store.removeCredentials(Domain.global(), mock(Credentials.class)));
            assertFalse(store.updateCredentials(Domain.global(), mock(Credentials.class), mock(Credentials.class)));
        }
    }

    @Nested
    @DisplayName("Other Public Methods")
    class OtherMethods {

        @Test
        @DisplayName("getContext should return the Jenkins instance")
        void getContextShouldReturnJenkins() {
            assertEquals(jenkinsMock, store.getContext());
        }

        @Test
        @DisplayName("getDisplayName should return the correct name")
        void getDisplayNameShouldReturnCorrectName() {
            assertEquals("Bitwarden", store.getDisplayName());
        }

        @Test
        @DisplayName("hasPermission2 should delegate to the Jenkins ACL")
        void hasPermission2ShouldDelegate() {
            Permission testPermission = Permission.READ;
            store.hasPermission2(authenticationMock, testPermission);
            verify(aclMock, times(1)).hasPermission2(authenticationMock, testPermission);
        }

        @Test
        @DisplayName("getStoreAction should return a valid action")
        void getStoreActionShouldReturnAction() {
            BitwardenCredentialsStore.BitwardenCredentialStoreAction action =
                    (BitwardenCredentialsStore.BitwardenCredentialStoreAction) store.getStoreAction();
            assertNotNull(action);
            assertEquals(store, action.getStore());
            assertEquals("Bitwarden", action.getDisplayName());
        }
    }
}
