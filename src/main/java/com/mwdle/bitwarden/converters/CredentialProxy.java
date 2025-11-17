package com.mwdle.bitwarden.converters;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.model.BitwardenItem;
import hudson.model.Descriptor;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.logging.Logger;

import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 * A stateless, lazy-loading proxy handler for Bitwarden-backed credentials.
 * <p>
 * This is the core of the plugin's lazy-loading mechanism. It acts as an intermediary for all
 * credential method calls. For non-secret data (like ID or description), it returns values
 * instantly from memory. For secret data (passwords, keys), it performs a one-time, live call
 * to the Bitwarden CLI to fetch the fresh secret at the moment it is needed.
 */
public class CredentialProxy implements InvocationHandler, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(CredentialProxy.class.getName());

    private final String credentialId; // The ID Jenkins knows this credential by (name or UUID)
    private final String itemId; // The actual Bitwarden UUID for fetching
    private final String itemName;
    private final String itemDescription;
    private final transient Descriptor<?> itemDescriptor;

    /**
     * Caches the fully resolved Jenkins credential object after the first time a secret is requested.
     * The {@code volatile} keyword ensures that changes to this field are visible across all threads.
     */
    private transient volatile StandardCredentials resolvedCredential;

    /**
     * Constructs a new proxy handler for a Bitwarden credential.
     * <p>
     * This constructor also builds the user-facing description string, intelligently formatting
     * it to provide extra context for file-based credentials and items with non-unique names.
     *
     * @param credentialId   The ID this credential will be known by in Jenkins (either the name or UUID).
     * @param itemId         The unique, persistent UUID of the item in Bitwarden.
     * @param itemName       The user-provided name of the item in Bitwarden.
     * @param itemDescriptor The Jenkins {@link Descriptor} for the credential type this proxy represents.
     */
    public CredentialProxy(String credentialId, String itemId, String itemName, Descriptor<?> itemDescriptor) {
        this.credentialId = credentialId;
        this.itemId = itemId;
        this.itemName = itemName;
        this.itemDescriptor = itemDescriptor;

        boolean isFileType = FileCredentials.class.isAssignableFrom(itemDescriptor.clazz);
        boolean isDuplicate = !credentialId.equals(this.itemName);

        String duplicateLabel = isDuplicate ? ", " + Messages.description_nonUniqueLabel() : "";
        String idString = String.format("%s %s%s", Messages.description_idLabel(), this.itemId, duplicateLabel);
        if (isFileType) {
            this.itemDescription = idString;
        } else {
            this.itemDescription = String.format("%s (%s)", this.itemName, idString);
        }
    }

    /**
     * Intercepts all method calls made to the proxied credential object.
     * <p>
     * This is the main entry point for the proxy. It handles non-secret methods instantly and
     * triggers the one-time, thread-safe resolution of the full secret when required.
     *
     * @param proxy  The proxy instance that the method was invoked on.
     * @param method The {@code Method} instance corresponding to the interface method invoked on the proxy instance.
     * @param args   An array of objects containing the values of the arguments passed in the method invocation.
     * @return The value to return from the method invocation on the proxy instance.
     * @throws IOException          if the underlying secret resolution fails.
     * @throws InterruptedException if the thread is interrupted during secret resolution.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws IOException, InterruptedException {
        String methodName = method.getName();

        // "Fast path" for non-secret methods. These are handled instantly without any blocking calls.
        switch (methodName) {
            case "getDescriptor":
                return this.itemDescriptor;
            case "getId":
                return this.credentialId;
            case "getDescription":
                return this.itemDescription;
            case "getScope":
                return CredentialsScope.GLOBAL;
            case "forRun":
                return proxy;
            case "toString":
                return "BitwardenCredentialProxy(itemId=" + this.itemId + ")";
            case "hashCode":
                return this.itemId.hashCode();
            case "getFileName":
                return this.itemName;
            case "isUsernameSecret":
                return true; // Always treat the username field as secret for each credential type containing a username
            case "getPassphrase":
                return Secret.fromString(""); // Bitwarden does not have a passphrase field for SSH Key secrets.
        }

        // "Slow path" for secret-related methods.
        // The double-checked locking pattern ensures the expensive resolveFullCredential()
        // method is only ever called once, in a thread-safe manner.
        if (resolvedCredential == null) {
            synchronized (this) {
                if (resolvedCredential == null) {
                    resolvedCredential = resolveFullCredential();
                }
            }
        }

        try {
            return method.invoke(resolvedCredential, args);
        } catch (Exception e) {
            throw new UndeclaredThrowableException(e, "Failed to invoke method on resolved Bitwarden credential.");
        }
    }

    /**
     * Performs the one-time, expensive operation of fetching the full Bitwarden item from the CLI
     * and converting it into a real, concrete Jenkins credential implementation.
     *
     * @return The fully-realized Jenkins credential object.
     * @throws IOException          if the CLI command fails or the item is not found.
     * @throws InterruptedException if the thread is interrupted.
     */
    private StandardCredentials resolveFullCredential() throws IOException, InterruptedException {
        LOGGER.fine(() -> "Performing one-time lazy fetch for Bitwarden item: " + this.itemId);
        BitwardenItem item =
                BitwardenCLI.getItem(BitwardenSessionManager.getInstance().getSessionToken(), this.itemId);

        if (item == null) {
            throw new IOException("Bitwarden item with ID " + this.itemId + " not found or could not be parsed.");
        }

        CredentialConverter converter = CredentialConverter.findConverter(item);
        if (converter != null) {
            return converter.convert(CredentialsScope.GLOBAL, this.credentialId, this.itemDescription, item);
        }

        throw new IOException("No suitable converter found for Bitwarden item ID: " + this.itemId);
    }
}
