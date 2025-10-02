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
 * This provider is responsible for resolving Bitwarden credentials into real, usable Jenkins credentials.
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

    @Override
    public String getIconClassName() {
        return "symbol-icon plugin-bitwarden-credentials-provider";
    }
}
