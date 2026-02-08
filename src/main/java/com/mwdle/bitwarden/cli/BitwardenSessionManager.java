package com.mwdle.bitwarden.cli;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.BitwardenCredentialsProvider;
import com.mwdle.bitwarden.model.BitwardenStatus;
import hudson.security.ACL;
import hudson.util.Secret;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A thread-safe singleton that manages and caches a single, global Bitwarden session token.
 * <p>
 * This class ensures that the slow, network-intensive login and unlock operations required for
 * Bitwarden interactions are performed infrequently. It uses a high-performance, double-checked
 * locking pattern to provide concurrent access to the session token.
 */
public class BitwardenSessionManager {

    private static final BitwardenSessionManager INSTANCE = new BitwardenSessionManager();
    private static final Logger LOGGER = Logger.getLogger(BitwardenSessionManager.class.getName());
    private final Object lock = new Object();
    /**
     * The cached Bitwarden session token. This token is stored in memory and reused across
     * builds to prevent API rate-limiting and improve secret fetching performance. It is marked
     * as {@code volatile} to ensure its visibility across all threads.
     */
    private volatile Secret sessionToken;

    /**
     * A private constructor to prevent instantiation of this utility class.
     */
    private BitwardenSessionManager() {}

    /**
     * Provides global access to the single instance of this manager.
     *
     * @return The singleton instance of {@link BitwardenSessionManager}.
     */
    public static BitwardenSessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Provides thread-safe, high-performance access to a valid Bitwarden session token.
     * <p>
     * This method uses a double-checked locking pattern. The "fast path" checks the validity
     * of the token without acquiring a lock, handling the vast majority of calls. Only if the
     * token is invalid will it enter a synchronized block to perform the expensive re-authentication.
     *
     * @return A valid session token.
     * @throws IOException          If the login/unlock process fails.
     * @throws InterruptedException If the CLI command is interrupted.
     */
    public Secret getSessionToken() throws IOException, InterruptedException {
        if (isSessionValid()) {
            LOGGER.fine("Cached Bitwarden session token is valid. Returning cached token.");
            return sessionToken;
        }
        LOGGER.fine("Token invalid or missing. Attempting to acquire lock to refresh token.");
        synchronized (lock) {
            // Double-check if another thread renewed the token while we were waiting for the lock.
            if (isSessionValid()) {
                LOGGER.fine("Another thread refreshed the token while waiting for lock. Returning refreshed token.");
                return sessionToken;
            }
            // If we are the thread responsible for refreshing, perform the full login.
            BitwardenConfig config = BitwardenConfig.getInstance();
            StandardUsernamePasswordCredentials apiKey =
                    Jenkins.get().getExtensionList(CredentialsProvider.class).stream()
                            .filter(p -> !(p instanceof BitwardenCredentialsProvider))
                            .flatMap(p -> p
                                    .getCredentialsInItemGroup(
                                            StandardUsernamePasswordCredentials.class,
                                            Jenkins.get(),
                                            ACL.SYSTEM2,
                                            Collections.emptyList())
                                    .stream())
                            .filter(c -> c.getId().equals(config.getApiCredentialId()))
                            .findFirst()
                            .orElse(null);
            StringCredentials masterPassword = Jenkins.get().getExtensionList(CredentialsProvider.class).stream()
                    .filter(p -> !(p instanceof BitwardenCredentialsProvider))
                    .flatMap(p -> p
                            .getCredentialsInItemGroup(
                                    StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList())
                            .stream())
                    .filter(c -> c.getId().equals(config.getMasterPasswordCredentialId()))
                    .findFirst()
                    .orElse(null);

            if (apiKey == null || masterPassword == null) {
                throw new IOException(
                        "Could not find API Key or Master Password credentials. Cannot refresh Bitwarden session token.");
            }

            LOGGER.info("Found Bitwarden API key and Master Password. Fetching new Bitwarden session token.");
            return this.sessionToken = getNewSessionToken(apiKey, masterPassword, config.getServerUrl());
        }
    }

    /**
     * Performs a check to see if the cached session token is still valid by calling {@code bw status}.
     *
     * @return {@code true} if the token is present and the vault status is {@code unlocked}.
     */
    public boolean isSessionValid() {
        if (this.sessionToken == null) {
            LOGGER.fine("Session token is null — not valid.");
            return false;
        }
        try {
            BitwardenStatus response = BitwardenCLI.status(this.sessionToken);
            return response.getStatus().equals("unlocked");
        } catch (Exception e) {
            // If the status command fails for any reason, the token is considered invalid.
            LOGGER.warning("Failed to check Bitwarden session token status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Performs the full, blocking authentication sequence to get a new session token.
     * This involves logging out, setting the server, logging in, and unlocking the vault.
     *
     * @param apiKey         The API Key credential.
     * @param masterPassword The Master Password credential.
     * @param serverUrl      The Bitwarden server URL.
     * @return A new, valid session token.
     * @throws IOException          if a CLI command fails.
     * @throws InterruptedException if a CLI command is interrupted.
     */
    private Secret getNewSessionToken(
            StandardUsernamePasswordCredentials apiKey, StringCredentials masterPassword, String serverUrl)
            throws IOException, InterruptedException {
        BitwardenCLI.logout();
        if (serverUrl == null || serverUrl.isEmpty()) {
            LOGGER.fine("Server URL not set. Using default.");
            serverUrl = "https://vault.bitwarden.com";
        }
        BitwardenCLI.clearBitwardenAppData();
        BitwardenCLI.configServer(serverUrl);
        BitwardenCLI.login(apiKey);
        return BitwardenCLI.unlock(masterPassword);
    }

    /**
     * Invalidates the current session token.
     * <p>
     * The next call to {@link #getSessionToken()} will be forced to perform a full re-authentication.
     * This is typically called after the plugin's global configuration has been changed.
     */
    public void invalidateSessionToken() {
        this.sessionToken = null;
        LOGGER.info("Session token has been invalidated.");
    }
}
