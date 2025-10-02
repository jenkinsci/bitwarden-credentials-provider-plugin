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
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 * A stateless, lazy-loading proxy handler for Bitwarden-backed credentials.
 * <p>
 * This handler intercepts method calls to credential interfaces. For non-secret data
 * (like ID or description), it returns cached values. For secret data (passwords, keys),
 * it makes a live, on-demand call to the Bitwarden CLI to fetch the fresh secret.
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
    private transient volatile StandardCredentials resolvedCredential;

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

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws IOException, InterruptedException {
        String methodName = method.getName();

        // Handle non-secret methods immediately to avoid delays
        switch (methodName) {
            case "getDescriptor":
                return this.itemDescriptor;
            case "getId":
                return this.credentialId;
            case "getDescription":
                return itemDescription;
            case "getScope":
                return CredentialsScope.GLOBAL;
            case "forRun":
                return proxy;
            case "toString":
                return "BitwardenCredentialProxy(itemId=" + itemId + ")";
            case "hashCode":
                return itemId.hashCode();
            case "getFileName":
                return this.itemName;
            case "isUsernameSecret":
                return true; // Always treat the username field as secret for each credential type containing a username
            case "getPassphrase":
                return "";
        }

        // If a secret related method is called, perform the expensive operation of resolving the actual secret.
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
     * Performs the one-time, expensive operation of fetching the full Bitwarden item
     * and converting it into a real, concrete Jenkins credential implementation.
     *
     * @return The fully-realized Jenkins credential object.
     */
    private StandardCredentials resolveFullCredential() throws IOException, InterruptedException {
        LOGGER.fine(() -> "Performing one-time lazy fetch for Bitwarden item: " + itemId);
        BitwardenItem item =
                BitwardenCLI.getItem(BitwardenSessionManager.getInstance().getSessionToken(), this.itemId);

        if (item == null) {
            throw new IOException("Bitwarden item with ID " + itemId + " not found or could not be parsed.");
        }

        CredentialConverter converter = CredentialConverter.findConverter(item);
        if (converter != null) {
            return converter.convert(CredentialsScope.GLOBAL, this.credentialId, this.itemDescription, item);
        }

        throw new IOException("No suitable converter found for Bitwarden item ID: " + itemId);
    }
}
