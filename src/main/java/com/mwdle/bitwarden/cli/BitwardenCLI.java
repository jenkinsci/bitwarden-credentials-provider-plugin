package com.mwdle.bitwarden.cli;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mwdle.bitwarden.Messages;
import com.mwdle.bitwarden.PluginDirectoryProvider;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A utility class for executing Bitwarden CLI commands.
 * <p>
 * This class contains only static methods and holds no state. It is a thin wrapper around the {@code bw} executable,
 * responsible for the low-level logic of constructing and running commands and interpreting their results.
 */
public final class BitwardenCLI {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCLI.class.getName());
    // Disable JSON source inclusion to prevent parsing exceptions from leaking sensitive data into Jenkins logs
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private static final String KEY_BW_SESSION = "BW_SESSION";

    private BitwardenCLI() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the isolated Jenkins data directory for the Bitwarden CLI.
     *
     * @return The File object representing the 'bwcli' subdirectory within the plugin data directory.
     */
    private static File getBitwardenDataDir() {
        return new File(PluginDirectoryProvider.getPluginDataDirectory().getAbsolutePath(), "bwcli");
    }

    /**
     * Clears the Bitwarden CLI application data by deleting data.json.
     * Ensures the CLI always has a clean working state
     * to mitigate potential corruption caused by updating the CLI and/or using newer CLI versions.
     * See <a href="https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/issues/18">Issue #18</a> for more information.
     */
    public static void clearBitwardenAppData() {
        Path dataJsonPath = getBitwardenDataDir().toPath().resolve("data.json");
        try {
            if (Files.deleteIfExists(dataJsonPath))
                LOGGER.info(
                        "Bitwarden CLI application data file (data.json) was deleted successfully to ensure a clean state.");
            else LOGGER.fine("No existing Bitwarden CLI application data found, skipping deletion.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete Bitwarden CLI application data", e);
        }
    }

    /**
     * Creates a {@link ArgumentListBuilder} for a Bitwarden CLI command, using the managed executable.
     *
     * @param args The arguments to pass to the {@code bw} command (e.g., "login", "--apikey").
     * @return A configured ArgumentListBuilder instance.
     */
    private static ArgumentListBuilder bitwardenCommand(String... args) {
        String executablePath = BitwardenCLIManager.getInstance().getExecutablePath();
        ArgumentListBuilder command = new ArgumentListBuilder();
        command.add(executablePath);
        command.add("--nointeraction");
        command.add("--raw");
        command.add(args);
        return command;
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
        return executeCommand(bitwardenCommand("--version"), Map.of());
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
        Map<String, String> env = Map.of(
                "BW_CLIENTID", apiKey.getUsername(),
                "BW_CLIENTSECRET", apiKey.getPassword().getPlainText());
        try {
            executeCommand(bitwardenCommand("login", "--apikey"), env);
            LOGGER.info("Login successful.");
        } catch (IOException e) {
            if (e.getMessage().contains(BitwardenConnectionException.IDENTIFIER)) {
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
        try {
            executeCommand(bitwardenCommand("logout"), Map.of());
            LOGGER.info("Logout successful.");
        } catch (IOException ignored) {
            // If logout fails, we are likely already logged out. Regardless, the plugin resets the CLI data directory
            // on reauthentication.
        }
    }

    /**
     * Unlocks the vault using the Master Password and returns the session key.
     *
     * @param masterPassword The Jenkins credential containing the Bitwarden Master Password.
     * @return The session key for subsequent commands.
     * @throws IOException                      if the CLI command fails.
     * @throws BitwardenConnectionException     if a network error occurs.
     * @throws BitwardenAuthenticationException if the provided Master Password is incorrect.
     * @throws InterruptedException             if the thread is interrupted.
     */
    public static Secret unlock(StringCredentials masterPassword) throws IOException, InterruptedException {
        LOGGER.info("Unlocking vault.");
        Map<String, String> env =
                Map.of("BITWARDEN_MASTER_PASSWORD", masterPassword.getSecret().getPlainText());
        try {
            return Secret.fromString(
                    executeCommand(bitwardenCommand("unlock", "--passwordenv", "BITWARDEN_MASTER_PASSWORD"), env));
        } catch (IOException e) {
            if (e.getMessage().contains(BitwardenConnectionException.IDENTIFIER)) {
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
     * @param sessionKey The active session key to use for authentication.
     * @throws IOException                  if the CLI command fails.
     * @throws BitwardenConnectionException if a network error occurs.
     * @throws InterruptedException         if the thread is interrupted.
     */
    public static void sync(Secret sessionKey) throws IOException, InterruptedException {
        LOGGER.info("Syncing vault.");
        Map<String, String> env = Map.of(KEY_BW_SESSION, Secret.toString(sessionKey));
        try {
            executeCommand(bitwardenCommand("sync"), env);
            LOGGER.info("Vault sync complete.");
        } catch (IOException e) {
            if (e.getMessage().contains(BitwardenConnectionException.IDENTIFIER)) {
                throw new BitwardenConnectionException(Messages.exception_syncError(), e);
            }
            throw e; // Re-throw the original generic exception if it's not a known type
        }
    }

    /**
     * Fetches a list of all item metadata from the vault.
     *
     * @param sessionKey The active session key to use for authentication.
     * @return A List of {@link BitwardenItemMetadata} objects.
     * @throws IOException          if the CLI command fails or JSON parsing fails.
     * @throws InterruptedException if the command is interrupted.
     */
    public static List<BitwardenItemMetadata> listItemsMetadata(Secret sessionKey)
            throws IOException, InterruptedException {
        Map<String, String> env = Map.of(KEY_BW_SESSION, Secret.toString(sessionKey));
        String json = executeCommand(bitwardenCommand("list", "items"), env);
        List<BitwardenItemMetadata> metadataList = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        LOGGER.info(() -> "Successfully deserialized metadata for " + metadataList.size() + " items.");
        return metadataList;
    }

    /**
     * Fetches a single, complete item from the vault by its ID.
     *
     * @param sessionKey The active session key.
     * @param itemId       The UUID of the item to fetch.
     * @return A complete {@link BitwardenItem} object.
     * @throws IOException          if the CLI command fails or JSON parsing fails.
     * @throws InterruptedException if the command is interrupted.
     */
    public static BitwardenItem getItem(Secret sessionKey, String itemId) throws IOException, InterruptedException {
        LOGGER.fine(() -> "Fetching single vault item with ID: " + itemId);
        Map<String, String> env = Map.of(KEY_BW_SESSION, Secret.toString(sessionKey));
        String json = executeCommand(bitwardenCommand("get", "item", itemId), env);
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
        executeCommand(bitwardenCommand("config", "server", serverUrl), Map.of());
        LOGGER.info("Server URL configured successfully.");
    }

    /**
     * The low-level command executor. This is the only method that runs the Bitwarden CLI.
     * It ensures every command runs with the isolated data directory.
     *
     * @param args The configured ArgumentListBuilder for the command to run.
     * @param environment Map of environment variables. Cannot be null.
     * @return The standard output of the command as a trimmed String.
     * @throws IOException          if the command returns a non-zero exit code or fails to start.
     * @throws InterruptedException if the thread is interrupted.
     */
    private static String executeCommand(ArgumentListBuilder args, Map<String, String> environment)
            throws IOException, InterruptedException {
        LOGGER.fine(() -> "Executing command: " + args);

        Map<String, String> env = new HashMap<>(environment);
        env.put("BITWARDENCLI_APPDATA_DIR", getBitwardenDataDir().getAbsolutePath());

        Launcher launcher = new Launcher.LocalLauncher(TaskListener.NULL);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Proc process = launcher.launch()
                .cmds(args)
                .envs(env)
                .stdout(stdout)
                .stderr(stderr)
                .start();

        int exitCode = process.joinWithTimeout(5, TimeUnit.MINUTES, TaskListener.NULL);
        String output = stdout.toString(StandardCharsets.UTF_8).trim();
        String errors = stderr.toString(StandardCharsets.UTF_8).trim();

        if (process.isAlive()) {
            process.kill();
            throw new IOException("Bitwarden CLI command timed out after 5 minutes: " + args + ". Stderr: " + errors);
        } else if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ". Stderr: " + errors);
        } else if (!errors.isEmpty()) {
            LOGGER.fine(() -> "CLI Exit code is 0, but stderr is not empty: " + errors);
        }

        return output;
    }
}
