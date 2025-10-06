package com.mwdle.bitwarden;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.XStream2;
import jakarta.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

/**
 * A thread-safe singleton that manages the lifecycle of the Bitwarden item metadata cache.
 * <p>
 * This class is the single source of truth for the list of credentials from Bitwarden. It handles
 * asynchronous refreshing, persistence to disk for resilience, and provides safe, non-blocking
 * access for the Jenkins UI and other components.
 */
@Extension
public class BitwardenCacheManager {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCacheManager.class.getName());
    private static final String CACHE_NAME = "bitwardenItemsMetadata";
    private final transient Object cacheLock = new Object();
    private transient volatile LoadingCache<String, List<BitwardenItemMetadata>> itemMetadataCache;

    /**
     * Provides global access to the single instance of this manager.
     *
     * @return The singleton instance of {@link BitwardenCacheManager}.
     */
    public static BitwardenCacheManager getInstance() {
        return Jenkins.get().getExtensionList(BitwardenCacheManager.class).get(0);
    }

    /**
     * Helper method to get the file where the cache will be persisted.
     *
     * @return The {@link XmlFile} for the cache.
     */
    private XmlFile getCacheFile() {
        File pluginDir = PluginDirectoryProvider.getPluginDataDirectory();
        return new XmlFile(new XStream2(), new File(pluginDir, "cache.xml"));
    }

    /**
     * Triggers a non-destructive, asynchronous refresh of the cache.
     * This is the standard method for forcing an update from Bitwarden.
     */
    public void updateCache() {
        getCache().refresh(CACHE_NAME);
    }

    /**
     * Completely removes the credential list from the in-memory cache.
     * The next request for credentials will trigger a full, but non-blocking reload.
     * <p>
     * Credential requests that occur during a full reload will receive an empty list of credentials,
     * until the cache has finished repopulating.
     */
    public void invalidateCache() {
        getCache().invalidate(CACHE_NAME);
    }

    /**
     * Schedules a background task to update the Bitwarden item cache after Jenkins starts.
     * <p>
     * This method is automatically invoked by Jenkins's startup sequence. It only proceeds
     * if the plugin has been configured. The update is submitted to a background thread to
     * ensure this operation does not block or delay the main Jenkins startup process.
     */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public void triggerStartupCacheUpdate() {
        BitwardenConfig config = BitwardenConfig.getInstance();
        if (!config.isConfigured()) {
            LOGGER.info("Bitwarden plugin is not configured. Skipping initial cache update.");
            return;
        }
        Timer.get()
                .submit(this::updateCache); // Don't delay Jenkins startup, run the cache update in a separate thread.
    }

    /**
     * Provides a safe, non-blocking way to get the current list of credential metadata.
     * <p>
     * This method returns the current cached data immediately (or an empty list if not yet populated)
     * and triggers a background refresh if the data is stale or missing.
     *
     * @return The current list of {@link BitwardenItemMetadata}, which may be empty.
     */
    public List<BitwardenItemMetadata> getMetadata() {
        LoadingCache<String, List<BitwardenItemMetadata>> cache = getCache();
        List<BitwardenItemMetadata> metadata = cache.getIfPresent(CACHE_NAME);
        // Trigger a smart, non-blocking background refresh. The get() call will use the
        // asynchronous reload() method if the data is stale or missing.
        Timer.get().submit(() -> {
            try {
                cache.get(CACHE_NAME);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Background cache refresh failed.", e);
            }
        });
        return metadata != null ? metadata : Collections.emptyList();
    }

    /**
     * Performs the core logic of fetching the latest metadata from the Bitwarden CLI.
     * This is a blocking, network-intensive operation. It also persists the result to disk on success.
     *
     * @return A fresh list of {@link BitwardenItemMetadata}.
     * @throws IOException          if a CLI command fails.
     * @throws InterruptedException if the thread is interrupted.
     */
    private List<BitwardenItemMetadata> fetchData() throws IOException, InterruptedException {
        LOGGER.info("Bitwarden metadata cache is loading/refreshing...");
        BitwardenCLI.sync(BitwardenSessionManager.getInstance().getSessionToken());
        List<BitwardenItemMetadata> metadata = BitwardenCLI.listItemsMetadata(
                BitwardenSessionManager.getInstance().getSessionToken());
        try {
            getCacheFile().write(metadata);
            LOGGER.info("Successfully saved credential metadata cache to disk.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save credential metadata cache to disk.", e);
        }
        return metadata;
    }

    /**
     * Gets the singleton instance of the Guava {@link LoadingCache}.
     * <p>
     * This method uses a thread-safe, double-checked locking pattern to initialize the cache
     * on its first use. It configures the cache for periodic background refreshing and
     * attempts to populate it from the persisted XML file on disk after a Jenkins restart.
     *
     * @return The singleton cache instance.
     */
    private LoadingCache<String, List<BitwardenItemMetadata>> getCache() {
        LoadingCache<String, List<BitwardenItemMetadata>> result = itemMetadataCache;
        if (result == null) {
            synchronized (cacheLock) {
                result = itemMetadataCache;
                if (result == null) {
                    int cacheDurationMinutes = BitwardenConfig.getInstance().getCacheDuration();
                    LOGGER.info(
                            "Initializing Bitwarden metadata cache with a " + cacheDurationMinutes + " minute expiry.");

                    ListeningExecutorService executor = MoreExecutors.listeningDecorator(Timer.get());
                    result = CacheBuilder.newBuilder()
                            .refreshAfterWrite(cacheDurationMinutes, TimeUnit.MINUTES)
                            .build(new CacheLoader<>() {
                                @Override
                                @Nonnull
                                public List<BitwardenItemMetadata> load(@Nonnull String key)
                                        throws IOException, InterruptedException {
                                    return fetchData();
                                }

                                @Override
                                @Nonnull
                                public ListenableFuture<List<BitwardenItemMetadata>> reload(
                                        @Nonnull String key, @Nonnull List<BitwardenItemMetadata> oldValue) {
                                    return executor.submit(() -> fetchData());
                                }
                            });

                    try {
                        XmlFile cacheFile = getCacheFile();
                        if (cacheFile.exists()) {
                            @SuppressWarnings("unchecked")
                            List<BitwardenItemMetadata> persistedMetadata =
                                    (List<BitwardenItemMetadata>) cacheFile.read();
                            result.put(CACHE_NAME, persistedMetadata);
                            LOGGER.info("Successfully loaded " + persistedMetadata.size()
                                    + " credential metadata items from disk.");
                        }
                    } catch (IOException | ClassCastException e) {
                        LOGGER.log(Level.WARNING, "Could not load credential metadata cache from disk.", e);
                    }

                    itemMetadataCache = result;
                }
            }
        }
        return result;
    }
}
