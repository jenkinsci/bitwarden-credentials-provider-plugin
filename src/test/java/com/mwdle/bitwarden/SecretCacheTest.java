package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemType;
import hudson.util.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies the opt-in behaviour, invalidation, and failure handling of {@link SecretCache}.
 */
@WithJenkins
@DisplayName("SecretCache")
class SecretCacheTest {

    private final AtomicInteger loads = new AtomicInteger();

    private static BitwardenItem item(String id) {
        return new BitwardenItem(id, "Note", BitwardenItemType.SECURE_NOTE, Secret.fromString("secret"), null, null);
    }

    private SecretCache.ItemLoader countingLoader(String id) {
        return () -> {
            loads.incrementAndGet();
            return item(id);
        };
    }

    @BeforeEach
    void setUp() {
        SecretCache.getInstance().invalidate();
    }

    @AfterEach
    void tearDown() {
        BitwardenConfig.getInstance().setSecretCacheDuration(0);
        SecretCache.getInstance().invalidate();
    }

    @Test
    @DisplayName("is disabled by default and always delegates to the loader")
    void disabledByDefault(JenkinsRule ignored) throws Exception {
        assertEquals(0, BitwardenConfig.getInstance().getSecretCacheDuration());

        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));
        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));

        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("serves repeated lookups from memory when enabled")
    void cachesWhenEnabled(JenkinsRule ignored) throws Exception {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);

        BitwardenItem first = SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));
        BitwardenItem second = SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));
        SecretCache.getInstance().getItem("item-2", countingLoader("item-2"));

        assertSame(first, second);
        assertEquals(2, loads.get(), "each distinct item should be loaded exactly once");
    }

    @Test
    @DisplayName("reloads items after invalidation")
    void reloadsAfterInvalidate(JenkinsRule ignored) throws Exception {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);

        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));
        SecretCache.getInstance().invalidate();
        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));

        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("stops caching as soon as the duration is set back to 0")
    void disablingClearsCache(JenkinsRule ignored) throws Exception {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);
        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));

        BitwardenConfig.getInstance().setSecretCacheDuration(0);
        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));

        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("does not cache failures and preserves the exception type")
    void doesNotCacheFailures(JenkinsRule ignored) throws Exception {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);

        assertThrows(
                IOException.class,
                () -> SecretCache.getInstance().getItem("item-1", () -> {
                    throw new IOException("CLI boom");
                }));
        SecretCache.getInstance().getItem("item-1", countingLoader("item-1"));

        assertEquals(1, loads.get(), "a failed load must not be cached");
    }

    @Test
    @DisplayName("preserves InterruptedException from the loader and keeps the interrupt flag")
    void preservesInterruptedException(JenkinsRule ignored) {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> SecretCache.getInstance().getItem("item-1", () -> {
                        throw new InterruptedException("Interrupted");
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear the flag so Jenkins can shut down cleanly after the test.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("shares a single load between concurrent requests for the same item")
    void deduplicatesConcurrentLoads(JenkinsRule ignored) throws Exception {
        BitwardenConfig.getInstance().setSecretCacheDuration(60);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        SecretCache.ItemLoader slowLoader = () -> {
            loads.incrementAndGet();
            Thread.sleep(200);
            return item("item-1");
        };

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BitwardenItem>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return SecretCache.getInstance().getItem("item-1", slowLoader);
                }));
            }
            start.countDown();
            for (Future<BitwardenItem> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, loads.get());
    }
}
