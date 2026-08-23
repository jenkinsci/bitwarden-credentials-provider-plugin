package com.mwdle.bitwarden;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;

/**
 * A singleton that manages the lifecycle, background loading, and caching of Bitwarden item metadata.
 */
public final class CacheManager {

    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());
    private static final CacheManager INSTANCE = new CacheManager();
    private static final String CACHE_KEY = "bitwarden-item-metadata";

    private final Object lock = new Object();
    private volatile LoadingCache<String, List<BitwardenItemMetadata>> metadataCache;

    private CacheManager() {}

    /**
     * @return the singleton instance of this manager
     */
    @NonNull
    public static CacheManager getInstance() {
        return INSTANCE;
    }

    /**
     * Triggers a non-destructive, asynchronous refresh of the cache.
     */
    public void refreshCache() {
        getCache().refresh(CACHE_KEY);
    }

    /**
     * Invalidates the cache.
     */
    public void invalidateCache() {
        synchronized (lock) {
            metadataCache = null;
        }
    }

    /**
     * @return the cached list of metadata, or an empty list if retrieval fails
     */
    @NonNull
    public List<BitwardenItemMetadata> getMetadata() {
        try {
            return getCache().get(CACHE_KEY);
        } catch (ExecutionException e) {
            LOGGER.log(Level.WARNING, "Failed to fetch Bitwarden item metadata from cache", e);
            return Collections.emptyList();
        }
    }

    /**
     * Lazily initializes and returns the singleton instance of the Bitwarden metadata cache.
     *
     * @return the singleton cache instance
     */
    @NonNull
    private LoadingCache<String, List<BitwardenItemMetadata>> getCache() {
        LoadingCache<String, List<BitwardenItemMetadata>> cache = metadataCache;
        if (cache == null) {
            synchronized (lock) {
                cache = metadataCache;
                if (cache == null) {
                    cache = CacheBuilder.newBuilder()
                            .refreshAfterWrite(BitwardenConfig.getInstance().getCacheDuration(), TimeUnit.MINUTES)
                            .build(new CacheLoader<>() {
                                @Override
                                @NonNull
                                public List<BitwardenItemMetadata> load(@NonNull String key)
                                        throws IOException, InterruptedException {
                                    return fetchMetadata();
                                }

                                @Override
                                @NonNull
                                public ListenableFuture<List<BitwardenItemMetadata>> reload(
                                        @NonNull String key, @NonNull List<BitwardenItemMetadata> oldValue) {
                                    return MoreExecutors.listeningDecorator(Timer.get())
                                            .submit(CacheManager::fetchMetadata);
                                }
                            });
                    metadataCache = cache;
                }
            }
        }
        return cache;
    }

    /**
     * Synchronizes the Bitwarden CLI and returns the latest Bitwarden item metadata.
     *
     * @return a list of Bitwarden item metadata
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if a CLI command fails
     */
    @NonNull
    private static List<BitwardenItemMetadata> fetchMetadata() throws IOException, InterruptedException {
        BitwardenCli.sync(SessionManager.getInstance().getSessionKey());
        return BitwardenCli.listItemsMetadata(
                SessionManager.getInstance().getSessionKey());
    }
}
