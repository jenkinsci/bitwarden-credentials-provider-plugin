package com.mwdle.bitwarden;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;

/**
 * A singleton that manages the lifecycle, background loading, and caching of Bitwarden item metadata.
 */
@Extension
public final class BitwardenCacheManager {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCacheManager.class.getName());
    private static final String CACHE_KEY = "bitwarden_item_metadata";
    private final Object lock = new Object();

    @SuppressWarnings("squid:S3077")
    private volatile LoadingCache<String, List<BitwardenItemMetadata>> metadataCache;

    /**
     * Provides global access to the single instance of this manager.
     *
     * @return the singleton instance of {@link BitwardenCacheManager}
     */
    @NonNull
    public static BitwardenCacheManager getInstance() {
        return ExtensionList.lookupSingleton(BitwardenCacheManager.class);
    }

    /**
     * Schedules a background task to populate the cache after Jenkins starts.
     */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public void refreshCacheOnStartup() {
        BitwardenConfig config = BitwardenConfig.getInstance();
        if (!config.isConfigured()) {
            LOGGER.info(() -> "Plugin is not configured. Skipping initial cache update.");
            return;
        }
        Timer.get().submit(this::refreshCache); // Update the cache in a separate thread to not delay Jenkins startup.
    }

    /**
     * Triggers a non-destructive, asynchronous refresh of the cache.
     */
    public void refreshCache() {
        getCache().refresh(CACHE_KEY);
    }

    /**
     * Purges the metadata list from the in-memory cache.
     */
    public void invalidateCache() {
        getCache().invalidate(CACHE_KEY);
    }

    /**
     * Returns the cached credential metadata immediately (or an empty list if not yet populated)
     * and triggers a background refresh if the data is stale or missing.
     *
     * @return the possibly empty list of {@link BitwardenItemMetadata}
     */
    @NonNull
    public List<BitwardenItemMetadata> getMetadata() {
        LoadingCache<String, List<BitwardenItemMetadata>> cache = getCache();
        List<BitwardenItemMetadata> metadata = cache.getIfPresent(CACHE_KEY);
        // Trigger a background refresh if stale
        Timer.get().submit(() -> {
            try {
                cache.get(CACHE_KEY);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Background cache refresh failed.", e);
            }
        });
        return Optional.ofNullable(metadata).orElse(Collections.emptyList());
    }

    /**
     * Synchronously fetches the latest item metadata from the Bitwarden CLI.
     * This is a blocking, network-intensive operation.
     *
     * @return a fresh list of {@link BitwardenItemMetadata}
     * @throws IOException if a CLI command fails
     * @throws InterruptedException if the thread is interrupted
     */
    @NonNull
    private List<BitwardenItemMetadata> fetchMetadata() throws IOException, InterruptedException {
        BitwardenCLI.sync(BitwardenSessionManager.getInstance().getSessionToken());
        return BitwardenCLI.listItemsMetadata(
                BitwardenSessionManager.getInstance().getSessionToken());
    }

    /**
     * Lazily initializes and returns the singleton instance of the Bitwarden metadata cache.
     *
     * @return the singleton cache instance
     */
    @NonNull
    private LoadingCache<String, List<BitwardenItemMetadata>> getCache() {
        if (metadataCache == null) {
            synchronized (lock) {
                if (metadataCache == null) {
                    metadataCache = CacheBuilder.newBuilder()
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
                                            .submit(BitwardenCacheManager.this::fetchMetadata);
                                }
                            });
                }
            }
        }
        return metadataCache;
    }
}
