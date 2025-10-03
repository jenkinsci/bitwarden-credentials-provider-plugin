package com.mwdle.bitwarden;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.mwdle.bitwarden.converters.CredentialConverter;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;

/**
 * The main entry point for the plugin, responsible for providing Bitwarden-backed credentials to Jenkins.
 * <p>
 * This class is a singleton managed by Jenkins. Its primary role is to respond to requests for credentials
 * (from pipelines or the UI) by fetching metadata from the {@link BitwardenCacheManager} and converting
 * it into a list of lazy-loading {@link com.mwdle.bitwarden.converters.CredentialProxy} objects.
 */
@Extension
public class BitwardenCredentialsProvider extends CredentialsProvider {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCredentialsProvider.class.getName());
    private final transient BitwardenCredentialsStore store = new BitwardenCredentialsStore(this);

    /**
     * Provides global access to the single instance of this provider.
     *
     * @return The singleton instance of this provider.
     */
    public static BitwardenCredentialsProvider getInstance() {
        return CredentialsProvider.all().get(BitwardenCredentialsProvider.class);
    }

    /**
     * Provides the {@link BitwardenCredentialsStore} to the Jenkins UI for the global credentials context.
     *
     * @param object The context for which the store is being requested.
     * @return The singleton {@link BitwardenCredentialsStore} if the context is the Jenkins root, otherwise {@code null}.
     */
    @Override
    public CredentialsStore getStore(ModelObject object) {
        if (object instanceof Jenkins) {
            return store;
        }
        return null;
    }

    /**
     * The primary method for generating the list of all available Bitwarden credentials.
     * <p>
     * This method is safe for all consumers (UI and pipelines). It fetches the latest metadata from the
     * cache, intelligently assigns a Jenkins ID (using the name for unique items and the UUID for
     * items with duplicate names), and returns a list of credential proxies.
     * <p>
     * It will not block or throw exceptions if the cache is being refreshed or has failed to load,
     * and will instead return an empty list.
     *
     * @return A list of all available {@link Credentials} proxies from Bitwarden.
     */
    public List<Credentials> listCredentials() {
        if (!BitwardenConfig.getInstance().isConfigured()) {
            return Collections.emptyList();
        }

        List<BitwardenItemMetadata> bitwardenItemMetadata =
                BitwardenCacheManager.getInstance().getMetadata();

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
     * Called by Jenkins to get a list of credentials that are available in a given context.
     * <p>
     * This implementation delegates to {@link #listCredentials()} to get all available proxies
     * and then filters that list to return only the credentials of the requested type.
     *
     * @param type               The class of credentials being requested.
     * @param itemGroup          The context in which the credentials are being requested.
     * @param authentication     The authentication context of the user or process.
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

    /**
     * Provides the CSS class name for the SVG symbol used as this provider's icon.
     * The corresponding SVG file is located in {@code src/main/resources/images/symbols/}.
     *
     * @return The CSS class name for the icon.
     */
    @Override
    public String getIconClassName() {
        return "symbol-icon plugin-bitwarden-credentials-provider";
    }
}
