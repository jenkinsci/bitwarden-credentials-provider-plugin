package com.mwdle.bitwarden.cli;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.BitwardenCredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.IOException;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A singleton that caches a Bitwarden CLI session key to prevent API rate-limiting and improve performance.
 */
public final class BitwardenSessionManager {

    private static final BitwardenSessionManager INSTANCE = new BitwardenSessionManager();
    private final Object lock = new Object();
    private volatile Secret sessionKey;

    private BitwardenSessionManager() {}

    /**
     * @return The singleton instance of this manager.
     */
    @NonNull
    public static BitwardenSessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * @return {@code true} if the Bitwarden session key is present.
     */
    public boolean isSessionValid() {
        return sessionKey != null;
    }

    /**
     * Invalidates the current Bitwarden CLI session.
     */
    public void invalidateSession() {
        synchronized (lock) {
            sessionKey = null;
        }
    }

    /**
     * @return a Bitwarden CLI session key
     * @throws InterruptedException if the Bitwarden CLI command is interrupted
     * @throws IOException if the login/unlock process fails
     */
    @NonNull
    public Secret getSessionKey() throws IOException, InterruptedException {
        Secret key = sessionKey;
        if (key == null) {
            synchronized (lock) {
                key = sessionKey;
                if (key == null) {
                    BitwardenConfig config = BitwardenConfig.getInstance();
                    StandardUsernamePasswordCredentials apiKey = lookupJenkinsCredential(
                            config.getApiCredentialId(), StandardUsernamePasswordCredentials.class);
                    StringCredentials masterPassword =
                            lookupJenkinsCredential(config.getMasterPasswordCredentialId(), StringCredentials.class);
                    if (apiKey == null || masterPassword == null) {
                        throw new IOException("API Key or Master Password missing; session refresh failed");
                    }
                    key = acquireSessionKey(apiKey, masterPassword, config.getServerUrl());
                    sessionKey = key;
                }
            }
        }
        return key;
    }

    /**
     * Invalidates the local Bitwarden CLI data, reauthenticates, unlocks the vault and returns the session key.
     *
     * @param apiKey the Jenkins Username Password credential containing Bitwarden API Key
     * @param masterPassword the Jenkins String credential containing Bitwarden Master Password
     * @param serverUrl the Bitwarden server URL
     * @return a new, valid session key
     * @throws InterruptedException if a CLI command is interrupted
     * @throws IOException if a CLI command fails
     */
    @NonNull
    private static Secret acquireSessionKey(
            @NonNull StandardUsernamePasswordCredentials apiKey,
            @NonNull StringCredentials masterPassword,
            @NonNull String serverUrl)
            throws IOException, InterruptedException {
        BitwardenCli.logout();
        BitwardenCli.configServer(serverUrl);
        BitwardenCli.login(apiKey);
        return BitwardenCli.unlock(masterPassword);
    }

    /**
     * Looks up a Jenkins credential by ID, bypassing this credential provider plugin to prevent infinite recursion.
     *
     * @param id the Jenkins credential ID to lookup
     * @param credentialClass the Class of the concrete credential type to find
     * @return the Jenkins credential of the specified type and ID, or {@code null} if not found
     * @param <T> the specific type of Jenkins credentials being requested
     */
    @CheckForNull
    private static <T extends StandardCredentials> T lookupJenkinsCredential(
            @CheckForNull String id, @NonNull Class<T> credentialClass) {
        if (id == null) {
            return null;
        }
        return ExtensionList.lookup(CredentialsProvider.class).stream()
                .filter(provider ->
                        provider.isEnabled(Jenkins.get()) && !(provider instanceof BitwardenCredentialsProvider))
                .flatMap(provider -> provider
                        .getCredentialsInItemGroup(credentialClass, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList())
                        .stream())
                .filter(credential -> credential.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
