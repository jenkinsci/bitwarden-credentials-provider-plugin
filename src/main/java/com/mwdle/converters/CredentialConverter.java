package com.mwdle.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.model.BitwardenItem;
import com.mwdle.model.BitwardenItemMetadata;
import hudson.ExtensionPoint;
import jenkins.model.Jenkins;

/**
 * Defines the contract for converting {@link BitwardenItemMetadata} into a lazy-loading Jenkins {@link StandardCredentials} object.
 * <p>
 * This abstract class is an {@link ExtensionPoint}, allowing different implementations to be
 * discovered by Jenkins at runtime. Each implementation is responsible for a specific
 * Bitwarden item type (e.g., Login, Secure Note) and creates a credential proxy that
 * fetches the actual secret data on-demand.
 */
public abstract class CredentialConverter implements ExtensionPoint {

    /**
     * Finds the first available and registered converter that can handle the given item metadata.
     *
     * @param metadata The lightweight, non-secret metadata of the Bitwarden item.
     * @return A suitable {@link CredentialConverter} instance, or {@code null} if none are found.
     */
    public static CredentialConverter findConverter(BitwardenItemMetadata metadata) {
        return Jenkins.get().getExtensionList(CredentialConverter.class).stream()
                .filter(converter -> converter.canConvert(metadata))
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds the first available and registered converter that can handle the given item.
     *
     * @param item The fully resolved Bitwarden item with all metadata and secret fields.
     * @return A suitable {@link CredentialConverter} instance, or {@code null} if none are found.
     */
    public static CredentialConverter findConverter(BitwardenItem item) {
        return Jenkins.get().getExtensionList(CredentialConverter.class).stream()
                .filter(converter -> converter.canConvert(item))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if this converter can handle the item type specified in the metadata.
     * <p>
     * Each implementation should check for its corresponding {@code BitwardenItemType}.
     *
     * @param metadata The lightweight, non-secret metadata of the Bitwarden item.
     * @return {@code true} if this converter can handle the item.
     */
    public abstract boolean canConvert(BitwardenItemMetadata metadata);

    /**
     * Checks if this converter can handle the secret fields in the item.
     *
     * @param item The fully resolved Bitwarden item with all metadata and secret fields.
     * @return {@code true} if this converter can handle the item.
     */
    public abstract boolean canConvert(BitwardenItem item);

    /**
     * Creates a Jenkins credential proxy from Bitwarden item metadata.
     */
    public abstract StandardCredentials createProxy(CredentialsScope scope, String id, BitwardenItemMetadata metadata);

    /**
     * Creates a real, fully-formed Jenkins credential from a complete Bitwarden item.
     * This is called by the proxy during lazy-loading.
     *
     * @param scope       The scope for the new credential.
     * @param id          The ID for the new credential.
     * @param item        The parsed JSON of the Bitwarden item.
     * @return The resulting Jenkins credential.
     */
    public abstract StandardCredentials convert(
            CredentialsScope scope, String id, String description, BitwardenItem item);
}
