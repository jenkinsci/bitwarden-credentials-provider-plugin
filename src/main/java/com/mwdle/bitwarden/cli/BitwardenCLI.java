package com.mwdle.bitwarden.cli;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.PluginDirectoryProvider;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenStatus;
import hudson.util.Secret;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A utility class for executing Bitwarden CLI commands.
 * <p>
 * This class contains only static methods and holds no state. It is a thin wrapper around the
 * {@code bw} executable, responsible for the low-level logic of constructing and running
 * {@link ProcessBuilder} commands and interpreting their results.
 */
public final class BitwardenCLI {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCLI.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * A private constructor to prevent instantiation of this utility class.
     */
    private BitwardenCLI() {}

    /**
     * Creates a {@link ProcessBuilder} for a Bitwarden CLI command, using the managed executable.
     *
     * @param command The arguments to pass to the {@code bw} command (e.g., "login", "--apikey").
     * @return A configured ProcessBuilder instance.
     */
    private static ProcessBuilder bitwardenCommand(String... command) {
        String executablePath = BitwardenCLIManager.getInstance().getExecutablePath();
        List<String> commandParts = new ArrayList<>();
        commandParts.add(executablePath);
        commandParts.addAll(Arrays.asList(command));
        LOGGER.fine(() -> "Building Bitwarden command: " + String.join(" ", commandParts));
        return new ProcessBuilder(commandParts);
    }

    /**
     * Fetches the version of the installed Bitwarden CLI.
     *
     * @return The version string from the CLI.
     * @throws IOException          if the CLI command fails.
     * @throws InterruptedException if the thread is interrupted.
     */
    public static String version() throws IOException, InterruptedException {
        LOGGER.info("Fetching Bitwarden CLI version");
        ProcessBuilder pb = bitwardenCommand("--version");
        return executeCommand(pb);
    }

    /**
     * Logs into the Bitwarden CLI using an API key.
     *
     * @param apiKey The Jenkins credential containing the Bitwarden Client ID and Client Secret.
     * @throws IOException                      if the CLI command fails.
     * @throws BitwardenConnectionException     if a network error occurs.
     * @throws BitwardenAuthenticationException if the provided API key is incorrect.
     * @throws InterruptedException             if the thread is interrupted.
     */
    public static void login(StandardUsernamePasswordCredentials apiKey) throws IOException, InterruptedException {
        LOGGER.info("Logging in with API key credentials.");
        ProcessBuilder pb = bitwardenCommand("login", "--apikey");
        Map<String, String> env = pb.environment();
        env.put("BW_CLIENTID", apiKey.getUsername());
        env.put("BW_CLIENTSECRET", apiKey.getPassword().getPlainText());
        try {
            executeCommand(pb);
            LOGGER.info("Login successful.");
        } catch (IOException e) {
            if (e.getMessage().contains("FetchError")) {
                throw new BitwardenConnectionException(Messages.exception_connectionError(), e);
            } else if (e.getMessage().contains("Username or password is incorrect")
                    || e.getMessage().contains("Invalid API Key")
                    || e.getMessage().contains("Incorrect client_secret")) {
                throw new BitwardenAuthenticationException(Messages.exception_loginError(), e);
            }
            throw e; // Re-throw the original generic exception if it's not a known type
        }
    }

    /**
     * Logs out of the Bitwarden CLI. This is a best-effort operation, and failures are ignored.
     *
     * @throws InterruptedException if the thread is interrupted.
     */
    public static void logout() throws InterruptedException {
        LOGGER.info("Logging out...");
        ProcessBuilder pb = bitwardenCommand("logout");
        try {
            executeCommand(pb);
            LOGGER.info("Logout successful.");
        } catch (IOException ignored) {
            // If logout fails, we are likely already logged out, so we can safely ignore it.
        }
    }

    /**
     * Unlocks the vault using the Master Password and returns the session token.
     *
     * @param masterPassword The Jenkins credential containing the Bitwarden Master Password.
     * @return The session token for subsequent commands.
     * @throws IOException                      if the CLI command fails.
     * @throws BitwardenConnectionException     if a network error occurs.
     * @throws BitwardenAuthenticationException if the provided Master Password is incorrect.
     * @throws InterruptedException             if the thread is interrupted.
     */
    public static Secret unlock(StringCredentials masterPassword) throws IOException, InterruptedException {
        LOGGER.info("Unlocking vault.");
        ProcessBuilder pb = bitwardenCommand("unlock", "--raw", "--passwordenv", "BITWARDEN_MASTER_PASSWORD");
        Map<String, String> env = pb.environment();
        env.put("BITWARDEN_MASTER_PASSWORD", masterPassword.getSecret().getPlainText());
        try {
            return Secret.fromString(executeCommand(pb));
        } catch (IOException e) {
            if (e.getMessage().contains("FetchError")) {
                throw new BitwardenConnectionException(Messages.exception_connectionError(), e);
            } else if (e.getMessage().contains("Invalid master password")) {
                throw new BitwardenAuthenticationException(Messages.exception_unlockError(), e);
            }
            throw e; // Re-throw the original generic exception if it's not a known type
        }
    }

    /**
     * Syncs the local CLI database with the remote Bitwarden vault.
     *
     * @param sessionToken The active session token to use for authentication.
     * @throws IOException                  if the CLI command fails.
     * @throws BitwardenConnectionException if a network error occurs.
     * @throws InterruptedException         if the thread is interrupted.
     */
    public static void sync(Secret sessionToken) throws IOException, InterruptedException {
        LOGGER.info("Syncing vault.");
        ProcessBuilder pb = bitwardenCommand("sync");
        pb.environment().put("BW_SESSION", Secret.toString(sessionToken));
        try {
            executeCommand(pb);
            LOGGER.info("Vault sync complete.");
        } catch (IOException e) {
            if (e.getMessage().contains("FetchError")) {
                throw new BitwardenConnectionException(Messages.exception_syncError(), e);
            }
            throw e; // Re-throw the original generic exception if it's not a known type
        }
    }

    /**
     * Checks the status of the Bitwarden CLI session.
     *
     * @param sessionToken The session token to validate.
     * @return A {@link BitwardenStatus} object representing the current state.
     * @throws IOException          if the CLI command fails or JSON parsing fails.
     * @throws InterruptedException if the thread is interrupted.
     */
    public static BitwardenStatus status(Secret sessionToken) throws IOException, InterruptedException {
        LOGGER.fine("Fetching CLI status.");
        ProcessBuilder pb = bitwardenCommand("status");
        pb.environment().put("BW_SESSION", Secret.toString(sessionToken));
        String json = executeCommand(pb);
        LOGGER.fine(() -> "CLI status fetched successfully. JSON: " + json);
        return OBJECT_MAPPER.readValue(json, BitwardenStatus.class);
    }

    /**
     * Fetches a list of all item metadata from the vault.
     *
     * @param sessionToken The active session token to use for authentication.
     * @return A List of {@link BitwardenItemMetadata} objects.
     * @throws IOException          if the CLI command fails or JSON parsing fails.
     * @throws InterruptedException if the command is interrupted.
     */
    public static List<BitwardenItemMetadata> listItemsMetadata(Secret sessionToken)
            throws IOException, InterruptedException {
        ProcessBuilder pb = bitwardenCommand("list", "items");
        pb.environment().put("BW_SESSION", Secret.toString(sessionToken));
        String json = executeCommand(pb);
        List<BitwardenItemMetadata> metadataList = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        LOGGER.info(() -> "Successfully deserialized metadata for " + metadataList.size() + " items.");
        return metadataList;
    }

    /**
     * Fetches a single, complete item from the vault by its ID.
     *
     * @param sessionToken The active session token.
     * @param itemId       The UUID of the item to fetch.
     * @return A complete {@link BitwardenItem} object.
     * @throws IOException          if the CLI command fails or JSON parsing fails.
     * @throws InterruptedException if the command is interrupted.
     */
    public static BitwardenItem getItem(Secret sessionToken, String itemId) throws IOException, InterruptedException {
        LOGGER.fine(() -> "Fetching single vault item with ID: " + itemId);
        ProcessBuilder pb = bitwardenCommand("get", "item", itemId);
        pb.environment().put("BW_SESSION", Secret.toString(sessionToken));
        String json = executeCommand(pb);
        LOGGER.fine(() -> "Single vault item " + itemId + " fetched successfully.");
        return OBJECT_MAPPER.readValue(json, BitwardenItem.class);
    }

    /**
     * Configures the Bitwarden CLI to point to a specific server URL.
     *
     * @param serverUrl The URL of the self-hosted Bitwarden or Vaultwarden instance.
     * @throws IOException          if the CLI command fails.
     * @throws InterruptedException if the CLI command is interrupted.
     */
    public static void configServer(String serverUrl) throws IOException, InterruptedException {
        LOGGER.info(() -> "Configuring server URL: " + serverUrl);
        executeCommand(bitwardenCommand("config", "server", serverUrl));
        LOGGER.info("Server URL configured successfully.");
    }

    /**
     * The low-level command executor. This is the only method that directly runs a process.
     * It ensures every command runs with the isolated data directory.
     *
     * @param pb The configured ProcessBuilder for the command to run.
     * @return The standard output of the command as a trimmed String.
     * @throws IOException          if the command returns a non-zero exit code.
     * @throws InterruptedException if the thread is interrupted.
     */
    private static String executeCommand(ProcessBuilder pb) throws IOException, InterruptedException {
        LOGGER.fine(() -> "Executing command: " + String.join(" ", pb.command()));
        Map<String, String> env = pb.environment();
        File bitwardenDataDir =
                new File(PluginDirectoryProvider.getPluginDataDirectory().getAbsolutePath(), "bwcli");
        env.put("BITWARDENCLI_APPDATA_DIR", bitwardenDataDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Only throw the raw exception. The calling method is responsible for interpreting it.
            throw new IOException("Command failed with exit code " + exitCode + ". Output: " + output);
        }
        return output.toString().trim();
    }
}
