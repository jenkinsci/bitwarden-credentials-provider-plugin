package com.mwdle.bitwarden;

import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.ItemGroup;
import hudson.security.Permission;
import jakarta.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.export.ExportedBean;
import org.springframework.security.core.Authentication;

/**
 * A simple, stateless view of the {@link BitwardenCredentialsProvider} for the Jenkins UI.
 * <p>
 * This class's only responsibility is to provide a list of Bitwarden credentials to be displayed
 * in the Jenkins "Credentials" page. It acts as a read-only view and delegates all
 * credential-listing logic to the provider.
 */
public class BitwardenCredentialsStore extends CredentialsStore {

    private final transient BitwardenCredentialsProvider provider;
    private final transient BitwardenCredentialStoreAction action;

    /**
     * Constructs the store.
     *
     * @param provider The provider that this store will represent in the UI.
     */
    public BitwardenCredentialsStore(BitwardenCredentialsProvider provider) {
        super(BitwardenCredentialsProvider.class);
        this.provider = provider;
        action = new BitwardenCredentialStoreAction(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public ItemGroup<?> getContext() {
        return Jenkins.get();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates permission checks to the Jenkins root ACL.
     */
    @Override
    public boolean hasPermission2(@Nonnull Authentication a, @Nonnull Permission permission) {
        return Jenkins.get().getACL().hasPermission2(a, permission);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CredentialsStoreAction getStoreAction() {
        return action;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return Messages.BitwardenCredentialsStore_DisplayName();
    }

    /**
     * Unsupported operation. Credentials must be managed in Bitwarden.
     *
     * @return always {@code false}.
     */
    @Override
    public boolean addCredentials(@Nonnull Domain domain, @Nonnull Credentials credentials) {
        return false;
    }

    /**
     * Unsupported operation. Credentials must be managed in Bitwarden.
     *
     * @return always {@code false}.
     */
    @Override
    public boolean removeCredentials(@Nonnull Domain domain, @Nonnull Credentials credentials) {
        return false;
    }

    /**
     * Unsupported operation. Credentials must be managed in Bitwarden.
     *
     * @return always {@code false}.
     */
    @Override
    public boolean updateCredentials(
            @Nonnull Domain domain, @Nonnull Credentials current, @Nonnull Credentials replacement) {
        return false;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the list of credentials to be displayed in the UI. This method delegates
     * to the provider's safe {@code listCredentials} method.
     */
    @Nonnull
    @Override
    public List<Credentials> getCredentials(@Nonnull Domain domain) {
        if (!hasPermission2(Jenkins.getAuthentication2(), CredentialsProvider.VIEW)) {
            return Collections.emptyList();
        }
        if (!Domain.global().equals(domain)) {
            return Collections.emptyList();
        }

        return provider.listCredentials();
    }

    /**
     * The UI action that makes this store visible in the Jenkins sidebar and credentials list.
     */
    @ExportedBean
    public static class BitwardenCredentialStoreAction extends CredentialsStoreAction {
        private final BitwardenCredentialsStore store;

        /**
         * Constructs the action.
         * @param store The store that this action represents.
         */
        private BitwardenCredentialStoreAction(BitwardenCredentialsStore store) {
            this.store = store;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nonnull
        public CredentialsStore getStore() {
            return store;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.BitwardenCredentialsStore_DisplayName();
        }
    }
}
