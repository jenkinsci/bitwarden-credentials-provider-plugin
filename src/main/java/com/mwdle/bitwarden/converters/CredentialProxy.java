package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 * A lazy-loading proxy for Bitwarden credentials.
 * <p>
 * Provides non-secret data (ID, description) instantly from memory, and defers
 * fetching secrets via the Bitwarden CLI until the exact moment they are requested.
 */
public final class CredentialProxy implements InvocationHandler, Serializable {
    @Serial
    private static final long serialVersionUID = 2L;
    /** The class of the converter that created this proxy. */
    private final Class<? extends CredentialConverter> converterClass;
    /** The Jenkins ID for this credential. */
    private final String credentialId;
    /** The Bitwarden item UUID. */
    private final String itemId;
    /** The Bitwarden item name. */
    private final String itemName;
    /** The concrete implementation class this credential represents. */
    private final Class<? extends StandardCredentials> credentialClass;
    /** The concrete Jenkins credential object to defer requests to. */
    private transient StandardCredentials resolvedCredential;

    /**
     * Constructs a new proxy handler for a Bitwarden credential.
     *
     * @param credentialId the ID this credential will be known by in Jenkins (either the name or UUID)
     * @param itemId the unique, persistent UUID of the item in Bitwarden
     * @param itemName the user-provided name of the item in Bitwarden
     * @param credentialClass the concrete Jenkins credential type this proxy represents
     * @param converterClass the class of the converter that created this proxy
     */
    private CredentialProxy(
            @NonNull String credentialId,
            @NonNull String itemId,
            @NonNull String itemName,
            @NonNull Class<? extends StandardCredentials> credentialClass,
            @NonNull Class<? extends CredentialConverter> converterClass) {
        this.credentialId = credentialId;
        this.itemId = itemId;
        this.itemName = itemName;
        this.credentialClass = credentialClass;
        this.converterClass = converterClass;
    }

    /**
     * Returns a Jenkins credential proxy.
     *
     * @param id the Jenkins credential ID
     * @param metadata the Bitwarden item metadata
     * @param credentialInterface the standard credential interface this proxy implements
     * @param credentialClass the concrete Jenkins credential type this proxy represents
     * @param converterClass the class of the converter that created this proxy
     * @param <T> the type of the standard credential interface
     * @return a proxy implementing the specified standard credential interface
     */
    @NonNull
    public static <T extends StandardCredentials> T create(
            @NonNull String id,
            @NonNull BitwardenItemMetadata metadata,
            @NonNull Class<T> credentialInterface,
            @NonNull Class<? extends T> credentialClass,
            @NonNull Class<? extends CredentialConverter> converterClass) {
        CredentialProxy handler = new CredentialProxy(id, metadata.id, metadata.name, credentialClass, converterClass);
        Object proxy = Proxy.newProxyInstance(
                credentialInterface.getClassLoader(), new Class<?>[] {credentialInterface}, handler);
        return credentialInterface.cast(proxy);
    }

    @Override
    @CheckForNull
    public Object invoke(@NonNull Object proxy, @NonNull Method method, @CheckForNull Object[] args) throws Throwable {
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        return switch (method.getName()) {
            case "getDescription" -> getDescription();
            case "getDescriptor" -> Jenkins.get().getDescriptorOrDie(credentialClass);
            case "getId" -> credentialId;
            case "getScope" -> CredentialsScope.GLOBAL;
            case "getFileName" -> itemName;
            case "hashCode" -> IdCredentials.Helpers.hashCode((IdCredentials) proxy);
            case "equals" -> {
                if (args == null || args.length != 1) yield false;
                yield IdCredentials.Helpers.equals((IdCredentials) proxy, args[0]);
            }
            default -> {
                try {
                    yield method.invoke(getResolvedCredential(), args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        };
    }

    /**
     * Returns a Bitwarden item from the CLI as a concrete Jenkins credential.
     *
     * @return the concrete Jenkins credential
     */
    @NonNull
    private synchronized StandardCredentials getResolvedCredential() {
        if (resolvedCredential == null) {
            BitwardenItem item;
            try {
                item = BitwardenCli.getItem(SessionManager.getInstance().getSessionKey(), itemId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Failed to fetch Bitwarden item: %s".formatted(itemId), e);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to fetch Bitwarden item: %s".formatted(itemId), e);
            }
            CredentialConverter converter = ExtensionList.lookupSingleton(converterClass);
            resolvedCredential = converter.convert(credentialId, getDescription(), item);
        }
        return resolvedCredential;
    }

    /**
     * Generates an intuitive credential description for the Jenkins UI that displays consistently across all converted Bitwarden credential types.
     *
     * @return the user-facing description for this credential
     */
    @NonNull
    private String getDescription() {
        boolean isDuplicate = !credentialId.equals(itemName);
        String duplicateLabel = isDuplicate ? ", " + Messages.description_nonUniqueLabel() : "";
        String idString = "%s %s%s".formatted(Messages.description_idLabel(), itemId, duplicateLabel);
        if (FileCredentials.class.isAssignableFrom(credentialClass)) return idString;
        return "%s (%s)".formatted(itemName, idString);
    }
}
