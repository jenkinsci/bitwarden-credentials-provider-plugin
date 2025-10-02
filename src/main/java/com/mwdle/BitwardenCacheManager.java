package com.mwdle;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.BitwardenCLI;
import com.mwdle.bitwarden.BitwardenSessionManager;
import com.mwdle.model.BitwardenItemMetadata;
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

@Extension
public class BitwardenCacheManager {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCacheManager.class.getName());
    private static final String CACHE_NAME = "bitwardenItemsMetadata";
    private final transient Object cacheLock = new Object();
    private transient volatile LoadingCache<String, List<BitwardenItemMetadata>> itemMetadataCache;

    /**
     * Provides global access to the single instance of this provider.
     *
     * @return The singleton instance of this provider.
     */
    public static BitwardenCacheManager getInstance() {
        return Jenkins.get().getExtensionList(BitwardenCacheManager.class).get(0);
    }

    /**
     * Helper method to get the file where the cache will be persisted.
     */
    private XmlFile getCacheFile() {
        File pluginDir = PluginDirectoryProvider.getPluginDataDirectory();
        return new XmlFile(new XStream2(), new File(pluginDir, "cache.xml"));
    }

    /**
     * Public method to allow external callers (like the global config) to update the cache.
     * It's safe to call this even before the cache is initialized.
     */
    public void updateCache() {
        getCache().refresh(CACHE_NAME);
    }

    public void invalidateCache() {
        getCache().invalidate(CACHE_NAME);
    }

    /**
     * Schedules a background task to prime the Bitwarden item cache after Jenkins starts.
     * <p>
     * This method is automatically invoked by Jenkins's startup sequence due to the
     * {@link Initializer} annotation. It only proceeds if the plugin has already been configured.
     * <p>
     * The cache update is submitted to a background thread to ensure this operation
     * does not block or delay the main Jenkins startup process.
     */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public void triggerStartupCacheUpdate() {
        BitwardenConfig config = BitwardenConfig.getInstance();
        if (!config.isConfigured()) {
            LOGGER.info("Bitwarden plugin is not configured. Skipping initial cache priming.");
            return;
        }
        Timer.get()
                .submit(this::updateCache); // Don't delay Jenkins startup, run the cache update in a separate thread.
    }

    public List<BitwardenItemMetadata> getMetadata() {
        LoadingCache<String, List<BitwardenItemMetadata>> cache = getCache();
        List<BitwardenItemMetadata> metadata = cache.getIfPresent(CACHE_NAME);
        Timer.get().submit(() -> {
            try {
                cache.get(CACHE_NAME);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Background cache refresh failed.", e);
            }
        });
        return metadata != null ? metadata : Collections.emptyList();
    }

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
