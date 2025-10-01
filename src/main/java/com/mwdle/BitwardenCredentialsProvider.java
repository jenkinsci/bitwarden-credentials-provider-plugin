package com.mwdle;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.mwdle.bitwarden.BitwardenCLI;
import com.mwdle.bitwarden.BitwardenSessionManager;
import com.mwdle.converters.CredentialConverter;
import com.mwdle.model.BitwardenItemMetadata;
import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import hudson.util.XStream2;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.springframework.security.core.Authentication;

/**
 * This provider is responsible for resolving Bitwarden credentials into real, usable Jenkins credentials.
 */
@Extension
public class BitwardenCredentialsProvider extends CredentialsProvider {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCredentialsProvider.class.getName());
    private final transient BitwardenCredentialsStore store = new BitwardenCredentialsStore(this);

    private static final String CACHE_NAME = "bitwardenItemsMetadata";
    private final transient Object cacheLock = new Object();
    private transient volatile LoadingCache<String, List<BitwardenItemMetadata>> itemMetadataCache;

    /**
     * Helper method to get the file where the cache will be persisted.
     */
    private XmlFile getCacheFile() {
        File pluginDir = PluginDirectoryProvider.getPluginDataDirectory();
        return new XmlFile(new XStream2(), new File(pluginDir, "cache.xml"));
    }

    /**
     * Provides global access to the single instance of this provider.
     *
     * @return The singleton instance of this provider.
     */
    public static BitwardenCredentialsProvider getInstance() {
        return CredentialsProvider.all().get(BitwardenCredentialsProvider.class);
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

    @Override
    public CredentialsStore getStore(ModelObject object) {
        if (object instanceof Jenkins) {
            return store;
        }
        return null;
    }

    /**
     *
     */
    public List<Credentials> listCredentials() {
        if (!BitwardenConfig.getInstance().isConfigured()) {
            return Collections.emptyList();
        }

        LoadingCache<String, List<BitwardenItemMetadata>> cache = getCache();
        List<BitwardenItemMetadata> bitwardenItemMetadata = cache.getIfPresent(CACHE_NAME);

        // The get() call will intelligently trigger a refresh ONLY if the data is
        // stale or missing, using the non-blocking reload() we already configured.
        Timer.get().submit(() -> {
            try {
                cache.get(CACHE_NAME);
            } catch (Exception e) {
                // The exception will be logged by the get() call itself, so we just need
                // to catch it here to prevent it from bubbling up in the background thread.
                LOGGER.log(Level.WARNING, "Background cache refresh failed.", e);
            }
        });

        if (bitwardenItemMetadata == null) {
            return Collections.emptyList();
        }

        Set<String> duplicateNames = bitwardenItemMetadata.stream()
                .map(BitwardenItemMetadata::getName)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        final List<Credentials> result = new ArrayList<>();
        bitwardenItemMetadata.forEach(metadata -> {
            CredentialConverter converter = CredentialConverter.findConverter(metadata);
            if (converter != null) {
                String jenkinsId;
                // If the name of this item is in our set of duplicates, use the UUID as the ID.
                if (duplicateNames.contains(metadata.getName())) {
                    jenkinsId = metadata.getId();
                } else {
                    // Otherwise, the name is unique, so use it as the ID.
                    jenkinsId = metadata.getName();
                }
                result.add(converter.createProxy(CredentialsScope.GLOBAL, jenkinsId, metadata));
            }
        });
        return result;
    }

    /**
     * Called by Jenkins whenever a build needs to resolve credentials. This implementation fetches the
     * complete list of items from the Bitwarden vault and dynamically converts them into Jenkins
     * credentials on the fly.
     * <p>
     * For each item retrieved from Bitwarden, this method creates <strong>two</strong> in-memory Jenkins
     * credentials:
     * <ol>
     * <li>One where the credential ID is the Bitwarden item's <strong>name</strong>.</li>
     * <li>One where the credential ID is the Bitwarden item's <strong>UUID</strong>.</li>
     * </ol>
     * This allows pipeline authors to reference the same secret using either its human-readable name or its
     * unique, stable ID (e.g., {@code credentialsId: 'My Production API Key'}) or
     * {@code credentialsId: 'a1b2c3d4-e5f6-...'}).
     *
     * @param type The class of credentials being requested.
     * @param itemGroup The context in which the credentials are being requested.
     * @param authentication The authentication context of the user or process.
     * @param domainRequirements Any domain requirements for the credentials.
     * @return A list of dynamically-generated credentials matching the request.
     */
    @Override
    @Nonnull
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @Nonnull Class<C> type,
            @Nullable ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @Nonnull List<DomainRequirement> domainRequirements) {

        LOGGER.fine(() -> "getCredentialsInItemGroup: type=" + type.getSimpleName()
                + " itemGroup=" + (itemGroup != null ? itemGroup.getFullName() : "null")
                + " authentication=" + (authentication != null ? authentication.getName() : "null"));
        if (itemGroup == null || authentication == null) {
            LOGGER.fine("getCredentialsInItemGroup: itemGroup or authentication is null — returning empty list");
            return Collections.emptyList();
        }

        List<Credentials> allCredentials = listCredentials();
        List<C> result = new ArrayList<>();
        for (Credentials c : allCredentials) {
            if (type.isInstance(c)) {
                result.add(type.cast(c));
            }
        }
        return result;
    }
}
