package com.mwdle.bitwarden;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.mwdle.bitwarden.model.BitwardenItem;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A singleton, opt-in, short-lived in-memory cache of resolved Bitwarden items (including secrets).
 * <p>
 * Disabled by default. When {@link BitwardenConfig#getSecretCacheDuration()} is greater than zero, resolved items are
 * kept in memory for at most that many seconds, so repeated credential lookups (for example, during frequent SCM
 * scans) do not each spawn a slow Bitwarden CLI process. Concurrent requests for the same item share a single CLI call.
 * <p>
 * Items are never persisted to disk, and secret fields remain wrapped in {@link hudson.util.Secret}. The cache is
 * cleared whenever the vault is re-synced or the plugin is reconfigured.
 */
public final class SecretCache {

    private static final SecretCache INSTANCE = new SecretCache();
    /** An upper bound on cached items, so a large vault cannot grow memory without limit. */
    private static final long MAXIMUM_SIZE = 1000;

    private final Object lock = new Object();
    private volatile Cache<String, BitwardenItem> cache;
    /** The duration, in seconds, that {@link #cache} was built with. */
    private volatile int cacheDuration;

    private SecretCache() {}

    /**
     * @return the singleton instance of this cache
     */
    @NonNull
    public static SecretCache getInstance() {
        return INSTANCE;
    }

    /**
     * Loads a Bitwarden item, typically by invoking the Bitwarden CLI.
     */
    @FunctionalInterface
    public interface ItemLoader {
        /**
         * @return the loaded Bitwarden item
         * @throws IOException if the item could not be loaded
         * @throws InterruptedException if loading is interrupted
         */
        @NonNull
        BitwardenItem load() throws IOException, InterruptedException;
    }

    /**
     * Returns the cached item for the given ID, or loads it with {@code loader} if it is absent or expired.
     * <p>
     * When secret caching is disabled, this always delegates to {@code loader}. Failed loads are never cached.
     *
     * @param itemId the Bitwarden item UUID
     * @param loader loads the item when it is not cached
     * @return the Bitwarden item
     * @throws IOException if the item could not be loaded
     * @throws InterruptedException if loading is interrupted
     */
    @NonNull
    public BitwardenItem getItem(@NonNull String itemId, @NonNull ItemLoader loader)
            throws IOException, InterruptedException {
        Cache<String, BitwardenItem> current = getCache();
        if (current == null) return loader.load();
        try {
            return current.get(itemId, loader::load);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) throw ioException;
            if (cause instanceof InterruptedException interruptedException) throw interruptedException;
            throw new IOException("Failed to load Bitwarden item: %s".formatted(itemId), cause);
        } catch (UncheckedExecutionException | ExecutionError e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    /**
     * Discards all cached items.
     */
    public void invalidate() {
        synchronized (lock) {
            Cache<String, BitwardenItem> current = cache;
            if (current != null) current.invalidateAll();
        }
    }

    /**
     * Returns the cache matching the configured duration, rebuilding it if the duration changed.
     *
     * @return the cache, or {@code null} if secret caching is disabled
     */
    @CheckForNull
    private Cache<String, BitwardenItem> getCache() {
        int duration = BitwardenConfig.getInstance().getSecretCacheDuration();
        Cache<String, BitwardenItem> current = cache;
        if (duration > 0 && current != null && cacheDuration == duration) return current;
        synchronized (lock) {
            if (duration <= 0) {
                if (cache != null) cache.invalidateAll();
                cache = null;
                return null;
            }
            if (cache == null || cacheDuration != duration) {
                if (cache != null) cache.invalidateAll();
                cache = CacheBuilder.newBuilder()
                        .expireAfterWrite(duration, TimeUnit.SECONDS)
                        .maximumSize(MAXIMUM_SIZE)
                        .build();
                cacheDuration = duration;
            }
            return cache;
        }
    }
}
