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
 * A simple, stateless view of the BitwardenCredentialsProvider for the Jenkins UI.
 * This class's only job is to provide a clean, de-duplicated list of credentials to UI components.
 */
public class BitwardenCredentialsStore extends CredentialsStore {

    private final transient BitwardenCredentialsProvider provider;
    private final transient BitwardenCredentialStoreAction action;

    public BitwardenCredentialsStore(BitwardenCredentialsProvider provider) {
        super(BitwardenCredentialsProvider.class);
        this.provider = provider;
        action = new BitwardenCredentialStoreAction(this);
    }

    @Override
    @Nonnull
    public ItemGroup<?> getContext() {
        return Jenkins.get();
    }

    @Override
    public boolean hasPermission2(@Nonnull Authentication a, @Nonnull Permission permission) {
        return Jenkins.get().getACL().hasPermission2(a, permission);
    }

    @Override
    public CredentialsStoreAction getStoreAction() {
        return action;
    }

    @Override
    public String getDisplayName() {
        return Messages.BitwardenCredentialsStore_DisplayName();
    }

    @Override
    public boolean addCredentials(@Nonnull Domain domain, @Nonnull Credentials credentials) {
        return false;
    }

    @Override
    public boolean removeCredentials(@Nonnull Domain domain, @Nonnull Credentials credentials) {
        return false;
    }

    @Override
    public boolean updateCredentials(
            @Nonnull Domain domain, @Nonnull Credentials current, @Nonnull Credentials replacement) {
        return false;
    }

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

    @ExportedBean
    public static class BitwardenCredentialStoreAction extends CredentialsStoreAction {
        private final BitwardenCredentialsStore store;

        private BitwardenCredentialStoreAction(BitwardenCredentialsStore store) {
            this.store = store;
        }

        @Override
        @Nonnull
        public CredentialsStore getStore() {
            return store;
        }

        @Override
        public String getDisplayName() {
            return Messages.BitwardenCredentialsStore_DisplayName();
        }
    }
}
