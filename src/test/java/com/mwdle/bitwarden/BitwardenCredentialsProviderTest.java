package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.CredentialsStoreAction;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import hudson.ExtensionList;
import hudson.security.ACL;
import hudson.util.Secret;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

/**
 * Integration tests for {@link BitwardenCredentialsProvider} and its read-only store, exercising the real
 * cache → provider → store chain with only the CLI/session boundary stubbed.
 */
@WithJenkins
@DisplayName("BitwardenCredentialsProvider")
class BitwardenCredentialsProviderTest {

    private static BitwardenCredentialsProvider provider() {
        return ExtensionList.lookupSingleton(BitwardenCredentialsProvider.class);
    }

    private static void configure() {
        BitwardenConfig config = BitwardenConfig.getInstance();
        config.setApiCredentialId("api-id");
        config.setMasterPasswordCredentialId("master-id");
    }

    /**
     * Stubs the CLI/session boundary so the cache loads the given metadata, then runs the supplied assertions.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void withMetadata(List<BitwardenItemMetadata> metadata, Runnable body) throws Exception {
        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.getSessionKey()).thenReturn(Secret.fromString("session"));
        try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class);
                MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {
            sessions.when(SessionManager::getInstance).thenReturn(sessionManager);
            cli.when(() -> BitwardenCli.listItemsMetadata(any())).thenReturn(metadata);
            CacheManager.getInstance().invalidateCache();
            try {
                body.run();
            } finally {
                CacheManager.getInstance().invalidateCache();
            }
        }
    }

    private static List<String> credentialIds(List<? extends Credentials> credentials) {
        return credentials.stream().map(c -> ((StandardCredentials) c).getId()).toList();
    }

    @Test
    @DisplayName("exposes the correct plugin icon class name")
    void exposesIconClassName(JenkinsRule ignored) {
        assertEquals(
                "symbol-icon plugin-bitwarden-credentials-provider", provider().getIconClassName());
    }

    @Nested
    @DisplayName("credential listing")
    class Listing {

        @Test
        @DisplayName("returns nothing when the plugin is not configured")
        void emptyWhenNotConfigured(JenkinsRule ignored) {
            List<Credentials> credentials =
                    provider().getCredentialsInItemGroup(Credentials.class, Jenkins.get(), ACL.SYSTEM2, List.of());

            assertTrue(credentials.isEmpty());
        }

        @Test
        @DisplayName("uses the name as the id for uniquely-named items and the UUID for duplicates")
        void assignsIdsByUniqueness(JenkinsRule ignored) throws Exception {
            configure();
            List<BitwardenItemMetadata> metadata = List.of(
                    new BitwardenItemMetadata("uuid-1", "dup", BitwardenItemType.LOGIN),
                    new BitwardenItemMetadata("uuid-2", "dup", BitwardenItemType.LOGIN),
                    new BitwardenItemMetadata("uuid-3", "uniq", BitwardenItemType.LOGIN));

            withMetadata(metadata, () -> {
                List<Credentials> credentials =
                        provider().getCredentialsInItemGroup(Credentials.class, Jenkins.get(), ACL.SYSTEM2, List.of());

                List<String> ids = credentialIds(credentials);
                assertEquals(3, ids.size());
                assertTrue(ids.contains("uuid-1"));
                assertTrue(ids.contains("uuid-2"));
                assertTrue(ids.contains("uniq"));
            });
        }

        @Test
        @DisplayName("skips item types that have no converter")
        void skipsUnsupportedTypes(JenkinsRule ignored) throws Exception {
            configure();
            List<BitwardenItemMetadata> metadata = List.of(
                    new BitwardenItemMetadata("uuid-1", "login", BitwardenItemType.LOGIN),
                    new BitwardenItemMetadata("uuid-2", "card", BitwardenItemType.CARD),
                    new BitwardenItemMetadata("uuid-3", "identity", BitwardenItemType.IDENTITY));

            withMetadata(metadata, () -> {
                List<Credentials> credentials =
                        provider().getCredentialsInItemGroup(Credentials.class, Jenkins.get(), ACL.SYSTEM2, List.of());

                assertEquals(List.of("login"), credentialIds(credentials));
            });
        }

        @Test
        @DisplayName("filters by the requested credential type")
        void filtersByType(JenkinsRule ignored) throws Exception {
            configure();
            List<BitwardenItemMetadata> metadata = List.of(
                    new BitwardenItemMetadata("uuid-1", "login", BitwardenItemType.LOGIN),
                    new BitwardenItemMetadata("uuid-2", "note", BitwardenItemType.SECURE_NOTE));

            withMetadata(metadata, () -> {
                List<org.jenkinsci.plugins.plaincredentials.StringCredentials> notes = provider()
                        .getCredentialsInItemGroup(
                                org.jenkinsci.plugins.plaincredentials.StringCredentials.class,
                                Jenkins.get(),
                                ACL.SYSTEM2,
                                List.of());

                assertEquals(List.of("note"), credentialIds(notes));
            });
        }

        @Test
        @DisplayName("returns nothing for non-system authentication")
        void emptyForNonSystemAuthentication(JenkinsRule ignored) throws Exception {
            configure();
            List<BitwardenItemMetadata> metadata =
                    List.of(new BitwardenItemMetadata("uuid-1", "login", BitwardenItemType.LOGIN));

            withMetadata(metadata, () -> {
                List<Credentials> credentials = provider()
                        .getCredentialsInItemGroup(Credentials.class, Jenkins.get(), Jenkins.ANONYMOUS2, List.of());

                assertTrue(credentials.isEmpty());
            });
        }
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("is exposed for the Jenkins context only")
        void storeForJenkinsContextOnly(JenkinsRule r) throws Exception {
            assertNotNull(provider().getStore(Jenkins.get()));
            assertNull(provider().getStore(r.createFreeStyleProject()));
        }

        @Test
        @DisplayName("is read-only")
        void isReadOnly(JenkinsRule ignored) throws Exception {
            CredentialsStore store = provider().getStore(Jenkins.get());
            assertNotNull(store);
            Domain domain = Domain.global();
            assertFalse(store.addCredentials(domain, mock(Credentials.class)));
            assertFalse(store.removeCredentials(domain, mock(Credentials.class)));
            assertFalse(store.updateCredentials(domain, mock(Credentials.class), mock(Credentials.class)));
        }

        @Test
        @DisplayName("exposes a store action bound to the store")
        void exposesStoreAction(JenkinsRule ignored) {
            CredentialsStore store = provider().getStore(Jenkins.get());
            assertNotNull(store);

            CredentialsStoreAction action = store.getStoreAction();
            assertNotNull(action);
            assertEquals(store, action.getStore());
        }
    }
}
