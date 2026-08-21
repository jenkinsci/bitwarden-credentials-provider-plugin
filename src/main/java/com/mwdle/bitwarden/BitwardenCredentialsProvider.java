package com.mwdle.bitwarden;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.CredentialsStoreAction;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.mwdle.bitwarden.converters.CredentialConverter;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.ItemGroup;
import hudson.model.ModelObject;
import hudson.security.ACL;
import hudson.security.Permission;
import java.util.*;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.springframework.security.core.Authentication;

/**
 * A Jenkins-managed singleton responsible for providing Bitwarden-backed credentials to Jenkins and consumers.
 */
@Extension
public final class BitwardenCredentialsProvider extends CredentialsProvider {

    private final BitwardenCredentialsStore store = new BitwardenCredentialsStore();

    /**
     * Schedules a background task to populate the cache after Jenkins starts.
     */
    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED)
    public static void triggerCacheRefresh() {
        if (BitwardenConfig.getInstance().isConfigured()) {
            Timer.get().submit(CacheManager.getInstance()::refreshCache);
        }
    }

    @Override
    @NonNull
    public String getIconClassName() {
        return "symbol-icon plugin-bitwarden-credentials-provider";
    }

    @Override
    @CheckForNull
    public CredentialsStore getStore(@CheckForNull ModelObject object) {
        if (object == Jenkins.get()) {
            return store;
        }
        return null;
    }

    @Override
    @NonNull
    public <C extends Credentials> List<C> getCredentialsInItemGroup(
            @NonNull Class<C> type,
            @Nullable ItemGroup itemGroup,
            @Nullable Authentication authentication,
            @NonNull List<DomainRequirement> domainRequirements) {
        if (!ACL.SYSTEM2.equals(authentication)) {
            return Collections.emptyList();
        }
        return getBitwardenCredentials().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    /**
     * Returns a list of all available Bitwarden credentials.
     * <p>
     * Fetches the latest metadata from the cache, intelligently assigns a Jenkins ID (using the BW name for unique
     * items and the BW UUID for items with duplicate names), and returns a list of credential proxies.
     * <p>
     * This method will not block or throw exceptions if the cache is being refreshed or has failed to load and will instead return an empty list.
     *
     * @return a list of all available {@link Credentials} from Bitwarden
     */
    @NonNull
    private static List<Credentials> getBitwardenCredentials() {
        if (!BitwardenConfig.getInstance().isConfigured()) {
            return Collections.emptyList();
        }

        List<BitwardenItemMetadata> bitwardenItemMetadata =
                CacheManager.getInstance().getMetadata();

        final Set<String> seenNames = new HashSet<>(bitwardenItemMetadata.size());
        final Set<String> duplicateNames = new HashSet<>();
        for (BitwardenItemMetadata metadata : bitwardenItemMetadata) {
            if (!seenNames.add(metadata.name)) {
                duplicateNames.add(metadata.name);
            }
        }

        final List<Credentials> credentials = new ArrayList<>(bitwardenItemMetadata.size());
        for (BitwardenItemMetadata metadata : bitwardenItemMetadata) {
            CredentialConverter converter = CredentialConverter.getConverter(metadata.type);
            if (converter != null) {
                String jenkinsId = duplicateNames.contains(metadata.name) ? metadata.id : metadata.name;
                credentials.add(converter.createProxy(jenkinsId, metadata));
            }
        }
        return credentials;
    }

    /**
     * A simple, stateless view of the {@link BitwardenCredentialsProvider} for the Jenkins UI.
     * <p>
     * This class's only responsibility is to provide a list of Bitwarden credentials to be displayed
     * in the Jenkins "Credentials" page. It acts as a read-only view and delegates all
     * credential-listing logic to the provider.
     */
    public static final class BitwardenCredentialsStore extends CredentialsStore {

        private final BitwardenCredentialStoreAction storeAction;

        /**
         * Constructs the store. Marked private so only the enclosing class ({@link BitwardenCredentialsProvider}) can construct it.
         */
        private BitwardenCredentialsStore() {
            super(BitwardenCredentialsProvider.class);
            storeAction = new BitwardenCredentialStoreAction();
        }

        @Override
        @NonNull
        public ModelObject getContext() {
            return Jenkins.get();
        }

        @Override
        public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission permission) {
            return Jenkins.get().hasPermission2(a, permission);
        }

        @Override
        @NonNull
        public List<Credentials> getCredentials(@NonNull Domain domain) {
            if (hasPermission2(Jenkins.getAuthentication2(), CredentialsProvider.VIEW)
                    && getDomains().contains(domain)) {
                return Collections.unmodifiableList(getBitwardenCredentials());
            }
            return Collections.emptyList();
        }

        /**
         * Unsupported operation. Credentials must be managed in Bitwarden.
         *
         * @return always {@code false}
         */
        @Override
        public boolean addCredentials(@NonNull Domain domain, @NonNull Credentials credentials) {
            return false;
        }

        /**
         * Unsupported operation. Credentials must be managed in Bitwarden.
         *
         * @return always {@code false}
         */
        @Override
        public boolean removeCredentials(@NonNull Domain domain, @NonNull Credentials credentials) {
            return false;
        }

        /**
         * Unsupported operation. Credentials must be managed in Bitwarden.
         *
         * @return always {@code false}
         */
        @Override
        public boolean updateCredentials(
                @NonNull Domain domain, @NonNull Credentials current, @NonNull Credentials replacement) {
            return false;
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.BitwardenCredentialsStore_DisplayName();
        }

        @Override
        @NonNull
        public CredentialsStoreAction getStoreAction() {
            return storeAction;
        }

        /**
         * Exposes this store within Jenkins.
         */
        public final class BitwardenCredentialStoreAction extends CredentialsStoreAction {

            /**
             * Constructs the store action. Marked private so only the enclosing class ({@link BitwardenCredentialsStore}) can construct it.
             */
            private BitwardenCredentialStoreAction() {}

            @Override
            @NonNull
            public CredentialsStore getStore() {
                return BitwardenCredentialsStore.this;
            }
        }
    }
}
