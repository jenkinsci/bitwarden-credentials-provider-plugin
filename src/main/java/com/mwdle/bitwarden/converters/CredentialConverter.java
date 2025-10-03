package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.ExtensionPoint;
import jenkins.model.Jenkins;

/**
 * Defines the contract for converting Bitwarden items into lazy-loading Jenkins credentials.
 * <p>
 * This abstract class is a Jenkins {@link ExtensionPoint}, allowing different implementations
 * to be discovered at runtime. Each implementation is responsible for a specific
 * Bitwarden item type (e.g., Login, Secure Note) and provides the logic for creating both
 * a lightweight proxy and a fully resolved credential object.
 */
public abstract class CredentialConverter implements ExtensionPoint {

    /**
     * Finds the first available and registered converter that can handle the given item metadata.
     * <p>
     * This static factory method iterates through all registered {@link CredentialConverter}
     * implementations and returns the first one that reports it can handle the item's type.
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
     * <p>
     * This static factory method is used during the lazy-loading process to ensure the correct
     * converter is used to create the final, concrete credential object.
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
     * This is a fast check based only on the item's type, without accessing any secret data.
     *
     * @param metadata The lightweight, non-secret metadata of the Bitwarden item.
     * @return {@code true} if this converter is designed for the item's type.
     */
    public abstract boolean canConvert(BitwardenItemMetadata metadata);

    /**
     * Checks if this converter can handle the fully resolved item.
     * <p>
     * This check is performed after the secret has been fetched and can be used to verify
     * that the necessary fields (e.g., {@code login}, {@code notes}) are present.
     *
     * @param item The fully resolved Bitwarden item with all metadata and secret fields.
     * @return {@code true} if this converter can handle the item's data.
     */
    public abstract boolean canConvert(BitwardenItem item);

    /**
     * Creates a lightweight, lazy-loading proxy for a Jenkins credential.
     * <p>
     * This method should not perform any expensive operations. It creates a dynamic proxy
     * that will defer the actual fetching of the secret until a method like {@code getPassword()}
     * or {@code getContent()} is called.
     *
     * @param scope    The scope for the new credential (always GLOBAL for this provider).
     * @param id       The ID the credential will be known by in Jenkins (either the name or UUID).
     * @param metadata The lightweight metadata of the Bitwarden item.
     * @return A {@link StandardCredentials} proxy object.
     */
    public abstract StandardCredentials createProxy(CredentialsScope scope, String id, BitwardenItemMetadata metadata);

    /**
     * Creates a real, fully-formed Jenkins credential from a complete Bitwarden item.
     * <p>
     * This method is called by the {@link CredentialProxy} during the lazy-loading process
     * after the full item has been fetched from the Bitwarden CLI.
     *
     * @param scope       The scope for the new credential.
     * @param id          The ID for the new credential.
     * @param description The user-facing description for the new credential.
     * @param item        The fully resolved Bitwarden item.
     * @return The final, concrete Jenkins credential object.
     */
    public abstract StandardCredentials convert(
            CredentialsScope scope, String id, String description, BitwardenItem item);
}
