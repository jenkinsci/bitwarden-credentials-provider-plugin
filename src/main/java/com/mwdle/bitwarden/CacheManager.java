package com.mwdle.bitwarden;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
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
@Extension
public final class CacheManager {

    private static final Logger LOGGER = Logger.getLogger(CacheManager.class.getName());
    private static final String CACHE_KEY = "bitwarden_item_metadata";
    private final Object lock = new Object();

    @SuppressWarnings("squid:S3077")
    private volatile LoadingCache<String, List<BitwardenItemMetadata>> metadataCache;

    /**
     * @return the singleton instance of this manager
     */
    @NonNull
    public static CacheManager getInstance() {
        return ExtensionList.lookupSingleton(CacheManager.class);
    }

    /**
     * Schedules a background task to populate the cache after Jenkins starts.
     */
    // TODO: code smell to make this class an extension just for the sake of adding an initializer? consider moving this to an existing extension class.
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public void refreshCacheOnStartup() {
        BitwardenConfig config = BitwardenConfig.getInstance();
        if (!config.isConfigured()) {
            LOGGER.info("Plugin is not configured. Skipping initial cache update.");
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
     * Invalidates the cache.
     */
    public void invalidateCache() {
        synchronized (lock) {
            metadataCache = null;
        }
    }

    /**
     * @return the list of metadata, or an empty list if retrieval fails
     */
    @NonNull
    public List<BitwardenItemMetadata> getMetadata() {
        try {
            return getCache().get(CACHE_KEY);
        } catch (ExecutionException e) {
            LOGGER.log(Level.WARNING, "Failed to fetch Bitwarden metadata from cache.", e);
            return Collections.emptyList();
        }
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
        BitwardenCLI.sync(SessionManager.getInstance().getSessionKey());
        return BitwardenCLI.listItemsMetadata(
                SessionManager.getInstance().getSessionKey());
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
                                            .submit(CacheManager.this::fetchMetadata);
                                }
                            });
                }
            }
        }
        return metadataCache;
    }
}
