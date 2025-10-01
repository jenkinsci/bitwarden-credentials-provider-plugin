package com.mwdle;

import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.ItemGroup;
import hudson.security.Permission;
import jakarta.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.jenkins.ui.icon.IconType;
import org.kohsuke.stapler.export.ExportedBean;
import org.springframework.security.core.Authentication;

/**
 * A simple, stateless view of the BitwardenCredentialsProvider for the Jenkins UI.
 * This class's only job is to provide a clean, de-duplicated list of credentials to UI components.
 */
public class BitwardenCredentialsStore extends CredentialsStore {

    private final transient BitwardenCredentialsProvider provider;
    private final transient BitwardenCredentialStoreAction action = new BitwardenCredentialStoreAction(this);

    public BitwardenCredentialsStore(BitwardenCredentialsProvider provider) {
        super(BitwardenCredentialsProvider.class);
        this.provider = provider;
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
        private static final String ICON_CLASS = "icon-bitwarden-credentials-store";
        private final BitwardenCredentialsStore store;

        private BitwardenCredentialStoreAction(BitwardenCredentialsStore store) {
            this.store = store;
            IconSet.icons.addIcon(new Icon(
                    ICON_CLASS + " icon-sm",
                    "plugin/bitwarden-credentials-provider/images/16x16/icon.svg",
                    Icon.ICON_SMALL_STYLE,
                    IconType.PLUGIN));
            IconSet.icons.addIcon(new Icon(
                    ICON_CLASS + " icon-md",
                    "plugin/bitwarden-credentials-provider/images/24x24/icon.svg",
                    Icon.ICON_MEDIUM_STYLE,
                    IconType.PLUGIN));
            IconSet.icons.addIcon(new Icon(
                    ICON_CLASS + " icon-lg",
                    "plugin/bitwarden-credentials-provider/images/32x32/icon.svg",
                    Icon.ICON_LARGE_STYLE,
                    IconType.PLUGIN));
            IconSet.icons.addIcon(new Icon(
                    ICON_CLASS + " icon-xlg",
                    "plugin/bitwarden-credentials-provider/images/48x48/icon.svg",
                    Icon.ICON_XLARGE_STYLE,
                    IconType.PLUGIN));
        }

        @Override
        @Nonnull
        public CredentialsStore getStore() {
            return store;
        }

        @Override
        public String getIconClassName() {
            return ICON_CLASS;
        }

        @Override
        public String getDisplayName() {
            return Messages.BitwardenCredentialsStore_DisplayName();
        }
    }
}
