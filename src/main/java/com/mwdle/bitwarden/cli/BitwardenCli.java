package com.mwdle.bitwarden.cli;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * A utility for executing Bitwarden CLI commands.
 */
public final class BitwardenCli {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCli.class.getName());

    // Disable JSON source inclusion to prevent parsing exceptions from leaking sensitive data into Jenkins logs
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private static final String ENV_BW_SESSION = "BW_SESSION";

    private BitwardenCli() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the version of the installed Bitwarden CLI
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command fails
     */
    @NonNull
    public static String version() throws InterruptedException, IOException {
        LOGGER.info("Fetching Bitwarden CLI version");
        return executeCommand(bitwardenCommand("--version"), Map.of());
    }

    /**
     * Configures the Bitwarden server URL.
     *
     * @param serverUrl the URL of the Bitwarden server
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command fails
     */
    public static void configServer(@NonNull String serverUrl) throws InterruptedException, IOException {
        LOGGER.log(Level.INFO, "Configuring server URL: {0}", serverUrl);
        executeCommand(bitwardenCommand("config", "server", serverUrl), Map.of());
    }

    /**
     * Logs into the vault.
     *
     * @param apiKey the Jenkins credential containing the Bitwarden API key
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command fails
     */
    public static void login(@NonNull StandardUsernamePasswordCredentials apiKey)
            throws InterruptedException, IOException {
        LOGGER.info("Logging into vault");
        Map<String, String> env = Map.of(
                "BW_CLIENTID", apiKey.getUsername(),
                "BW_CLIENTSECRET", apiKey.getPassword().getPlainText());
        executeCommand(bitwardenCommand("login", "--apikey"), env);
    }

    /**
     * Unlocks the vault and returns the session key.
     *
     * @param masterPassword the Jenkins credential containing the Bitwarden master password
     * @return an active session key
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command fails
     */
    @NonNull
    public static Secret unlock(@NonNull StringCredentials masterPassword) throws InterruptedException, IOException {
        LOGGER.info("Unlocking vault");
        Map<String, String> env =
                Map.of("BITWARDEN_MASTER_PASSWORD", masterPassword.getSecret().getPlainText());
        Secret sessionKey = Secret.fromString(
                executeCommand(bitwardenCommand("unlock", "--passwordenv", "BITWARDEN_MASTER_PASSWORD"), env));
        LOGGER.info("Unlocked vault successfully");
        return sessionKey;
    }

    /**
     * Synchronizes the vault with the Bitwarden server.
     *
     * @param sessionKey an active session key
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command fails
     */
    public static void sync(@NonNull Secret sessionKey) throws InterruptedException, IOException {
        LOGGER.fine("Syncing vault");
        Map<String, String> env = Map.of(ENV_BW_SESSION, Secret.toString(sessionKey));
        executeCommand(bitwardenCommand("sync"), env);
    }

    /**
     * Returns a list of all Bitwarden item metadata from the vault.
     *
     * @param sessionKey an active session key
     * @return a list of Bitwarden item metadata
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command or JSON parsing fails
     */
    @NonNull
    public static List<BitwardenItemMetadata> listItemsMetadata(@NonNull Secret sessionKey)
            throws InterruptedException, IOException {
        LOGGER.fine("Fetching list of Bitwarden item metadata from the vault");
        Map<String, String> env = Map.of(ENV_BW_SESSION, Secret.toString(sessionKey));
        String json = executeCommand(bitwardenCommand("list", "items"), env);
        List<BitwardenItemMetadata> metadataList = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        LOGGER.log(Level.FINE, "Fetched metadata for {0} items", metadataList.size());
        return metadataList;
    }

    /**
     * Returns a single Bitwarden item from the vault.
     *
     * @param sessionKey an active session key
     * @param itemId the UUID of the Bitwarden item to fetch
     * @return a Bitwarden item
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command or JSON parsing fails
     */
    @NonNull
    public static BitwardenItem getItem(@NonNull Secret sessionKey, @NonNull String itemId)
            throws InterruptedException, IOException {
        LOGGER.log(Level.FINE, "Fetching single vault item with ID: {0}", itemId);
        Map<String, String> env = Map.of(ENV_BW_SESSION, Secret.toString(sessionKey));
        String json = executeCommand(bitwardenCommand("get", "item", itemId), env);
        LOGGER.log(Level.FINE, "Fetched single vault item: {0}", itemId);
        return OBJECT_MAPPER.readValue(json, BitwardenItem.class);
    }

    /**
     * Logs out of the Bitwarden CLI.
     *
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the CLI executable path cannot be determined
     */
    public static void logout() throws InterruptedException, IOException {
        LOGGER.info("Logging out of vault and resetting Bitwarden CLI");
        ArgumentListBuilder command = bitwardenCommand("logout");
        try {
            executeCommand(command, Map.of());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Logout failed (likely already logged out)", e);
        }
        try {
            // See https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/issues/18
            Files.deleteIfExists(
                    BitwardenDirectoryProvider.getCliDataDirectory().toPath().resolve("data.json"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to reset Bitwarden CLI", e);
        }
    }

    /**
     * Creates an argument list builder for a {@code bw} command, using the managed executable.
     *
     * @param args the argument(s) to pass to the command (e.g., "login", "--apikey")
     * @return an argument list builder representing the command
     * @throws InterruptedException if automatic Bitwarden CLI provisioning is interrupted
     * @throws IOException if the CLI executable path cannot be determined
     */
    @NonNull
    private static ArgumentListBuilder bitwardenCommand(@NonNull String... args)
            throws InterruptedException, IOException {
        String executablePath = BitwardenCliManager.getExecutablePath();
        ArgumentListBuilder command = new ArgumentListBuilder();
        command.add(executablePath);
        command.add("--nointeraction");
        command.add("--raw");
        command.add(args);
        return command;
    }

    /**
     * Returns the standard output from executing the given Bitwarden CLI command with the specified environment variables.
     *
     * @param args an argument list builder representing the command to execute
     * @param environment a map of environment variables to use for command execution
     * @return the stripped standard output from executing the command
     * @throws InterruptedException if the command is interrupted
     * @throws IOException if the command returns a non-zero exit code or fails to start
     */
    @NonNull
    private static String executeCommand(@NonNull ArgumentListBuilder args, @NonNull Map<String, String> environment)
            throws InterruptedException, IOException {
        LOGGER.log(Level.FINE, "Executing command: {0}", args);

        Map<String, String> env = new HashMap<>(environment);
        env.put(
                "BITWARDENCLI_APPDATA_DIR",
                BitwardenDirectoryProvider.getCliDataDirectory().getAbsolutePath());

        Launcher launcher = new Launcher.LocalLauncher(TaskListener.NULL);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Proc process = launcher.launch()
                .cmds(args)
                .envs(env)
                .stdout(stdout)
                .stderr(stderr)
                .start();

        int exitCode = process.joinWithTimeout(3, TimeUnit.MINUTES, TaskListener.NULL);
        String output = stdout.toString(StandardCharsets.UTF_8).strip();
        String errors = stderr.toString(StandardCharsets.UTF_8).strip();

        if (exitCode != 0) {
            throw new IOException("Command failed with exit code %s. Stderr: %s".formatted(exitCode, errors));
        } else if (!errors.isEmpty()) {
            LOGGER.log(Level.FINE, "Command exit code is 0, but stderr is not empty: {0}", errors);
        }

        return output;
    }
}
