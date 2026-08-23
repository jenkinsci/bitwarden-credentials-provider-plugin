package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.util.Secret;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

/**
 * Verifies the lifecycle, exception handling, and caching mechanics of {@link CacheManager}.
 */
@WithJenkins
@DisplayName("CacheManager")
@SuppressWarnings("ResultOfMethodCallIgnored")
class CacheManagerTest {

    @BeforeEach
    void setUp() {
        CacheManager.getInstance().invalidateCache();
    }

    @Test
    @DisplayName("fetches metadata via CLI and caches the result on subsequent calls")
    void fetchesAndCachesMetadata(JenkinsRule ignored) throws Exception {
        List<BitwardenItemMetadata> mockMetadata = List.of(mock(BitwardenItemMetadata.class));

        try (MockedStatic<SessionManager> session = mockStatic(SessionManager.class);
                MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {

            SessionManager mockSessionManager = mock(SessionManager.class);
            session.when(SessionManager::getInstance).thenReturn(mockSessionManager);
            when(mockSessionManager.getSessionKey()).thenReturn(Secret.fromString("dummy-key"));

            cli.when(() -> BitwardenCli.listItemsMetadata(any())).thenReturn(mockMetadata);

            CacheManager manager = CacheManager.getInstance();

            List<BitwardenItemMetadata> firstCall = manager.getMetadata();
            assertEquals(1, firstCall.size());

            List<BitwardenItemMetadata> secondCall = manager.getMetadata();
            assertEquals(1, secondCall.size());

            cli.verify(() -> BitwardenCli.sync(any()), times(1));
            cli.verify(() -> BitwardenCli.listItemsMetadata(any()), times(1));
        }
    }

    @Test
    @DisplayName("returns an empty list gracefully when the CLI throws an exception")
    void handlesCliExceptionsGracefully(JenkinsRule ignored) throws Exception {
        try (MockedStatic<SessionManager> session = mockStatic(SessionManager.class);
                MockedStatic<BitwardenCli> cli = mockStatic(BitwardenCli.class)) {

            SessionManager mockSessionManager = mock(SessionManager.class);
            session.when(SessionManager::getInstance).thenReturn(mockSessionManager);
            when(mockSessionManager.getSessionKey()).thenReturn(Secret.fromString("dummy-key"));

            cli.when(() -> BitwardenCli.sync(any())).thenThrow(new IOException("CLI network timeout"));

            CacheManager manager = CacheManager.getInstance();

            List<BitwardenItemMetadata> result = manager.getMetadata();

            assertTrue(result.isEmpty(), "Expected an empty list when CLI execution fails");
        }
    }
}
