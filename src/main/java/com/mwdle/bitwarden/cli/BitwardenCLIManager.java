package com.mwdle.bitwarden.cli;

import com.mwdle.bitwarden.BitwardenConfig;
import com.mwdle.bitwarden.PluginDirectoryProvider;
import hudson.ProxyConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A thread-safe singleton that manages the lifecycle of the Bitwarden CLI executable.
 * <p>
 * This class handles OS detection, on-demand downloading from the official Bitwarden site,
 * extraction of the executable, and provides a reliable, cached path for other components to use.
 * It ensures the CLI is always available when needed, attempting to download it if it's missing.
 */
public final class BitwardenCLIManager {

    private static final BitwardenCLIManager INSTANCE = new BitwardenCLIManager();
    private static final Logger LOGGER = Logger.getLogger(BitwardenCLIManager.class.getName());
    private volatile String executablePath;
    private final transient Object provisionLock = new Object();

    /**
     * A private constructor to prevent instantiation of this utility class.
     */
    private BitwardenCLIManager() {}

    /**
     * Provides global access to the single instance of this manager.
     *
     * @return The singleton instance of {@link BitwardenCLIManager}.
     */
    public static BitwardenCLIManager getInstance() {
        return INSTANCE;
    }

    /**
     * Represents the operating systems supported by the Bitwarden CLI.
     */
    private enum OS {
        WINDOWS,
        MAC,
        LINUX;

        /**
         * Detects the current operating system based on the {@code os.name} system property.
         *
         * @return the detected {@link OS} enum constant.
         * @throws UnsupportedOperationException if the OS is not supported.
         */
        static OS detect() {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) return WINDOWS;
            if (osName.contains("mac")) return MAC;
            if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) return LINUX;
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
    }

    /**
     * Detects the current system architecture based on the {@code os.arch} system property and
     * throws an exception there is no compatible Bitwarden executable available for download from the Bitwarden website.
     *
     * @throws UnsupportedOperationException if the architecture is not supported for automatic download.
     */
    private void checkSupportedArchitecture() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (!"amd64".equals(arch) && !"x86_64".equals(arch)) {
            throw new UnsupportedOperationException(
                    "Automatic download of Bitwarden CLI is not supported on this CPU architecture: " + arch
                            + ". Please install the CLI manually and provide the path in the plugin configuration.");
        }
    }

    /**
     * Determines the correct download URL for the Bitwarden CLI based on the detected OS and architecture.
     *
     * @return A string containing the direct download URL.
     */
    private String getDownloadUrl() {
        checkSupportedArchitecture();
        OS os = OS.detect();
        return switch (os) {
            case WINDOWS -> "https://bitwarden.com/download/?app=cli&platform=windows";
            case MAC -> "https://bitwarden.com/download/?app=cli&platform=macos";
            case LINUX -> "https://bitwarden.com/download/?app=cli&platform=linux";
        };
    }

    /**
     * Determines the correct name for the Bitwarden CLI executable based on the detected OS.
     *
     * @return The name of the executable (e.g., "bw.exe" or "bw").
     */
    private String getExecutableName() {
        OS os = OS.detect();
        return (os == OS.WINDOWS) ? "bw.exe" : "bw";
    }

    /**
     * Downloads a zip archive from a URL using Jenkins' proxy settings, finds the {@code bw} executable
     * within it, extracts it to the target file, and sets it as executable.
     *
     * @param downloadUrl the URL of the zip archive to download.
     * @param targetFile  the destination file for the extracted executable.
     * @throws IOException if the download or extraction fails.
     * @throws InterruptedException if the download is interrupted.
     */
    private void downloadAndExtract(URI downloadUrl, File targetFile) throws IOException, InterruptedException {
        LOGGER.fine(() -> "Downloading Bitwarden CLI from URL: " + downloadUrl);
        File bwCliZip = File.createTempFile("bw-cli", ".zip");
        try {
            HttpClient client = ProxyConfiguration.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(downloadUrl).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Failed to download file. Status code: " + response.statusCode());
            }

            try (InputStream in = response.body()) {
                Files.copy(in, bwCliZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.fine("Downloaded zip to: " + bwCliZip.getAbsolutePath());
            }

            try (ZipFile zipFile = new ZipFile(bwCliZip)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                boolean foundExecutable = false;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().equalsIgnoreCase("bw")
                            || entry.getName().equalsIgnoreCase("bw.exe")) {
                        try (InputStream zipInputStream = zipFile.getInputStream(entry)) {
                            Files.copy(zipInputStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            LOGGER.fine("Extracted executable: " + targetFile.getAbsolutePath());
                            foundExecutable = true;
                            break;
                        }
                    }
                }
                if (!foundExecutable) {
                    throw new IOException("Could not find 'bw' or 'bw.exe' executable in the downloaded zip file.");
                }
            }
        } finally {
            Files.deleteIfExists(bwCliZip.toPath());
        }

        if (targetFile.setExecutable(true, true)) {
            LOGGER.fine("Downloaded Bitwarden CLI executable: " + targetFile.getAbsolutePath());
        } else {
            LOGGER.warning("Could not set executable permission on Bitwarden CLI.");
        }
    }

    /**
     * Forces a download of the latest Bitwarden CLI, overwriting any existing version.
     * <p>
     * This method performs a blocking, network-intensive operation performed in a thread-safe manner.
     *
     * @return {@code true} on success, {@code false} on failure.
     */
    // Jenkins Security Scan checks methods matching the Stapler web method naming scheme (e.g. doWhatever), but this method is not for Stapler.
    // lgtm[jenkins/no-permission-check]
    // lgtm[jenkins/csrf]
    public boolean downloadLatestExecutable() {
        synchronized (provisionLock) {
            LOGGER.info("Downloading and provisioning the latest Bitwarden CLI executable...");
            try {
                String downloadUrl = getDownloadUrl();
                String executableName = getExecutableName();
                File pluginBinDir = getPluginBinDirectory();
                File executableFile = new File(pluginBinDir, executableName);

                downloadAndExtract(new URI(downloadUrl), executableFile);
                this.executablePath = executableFile.getAbsolutePath();
                LOGGER.info("Successfully provisioned Bitwarden CLI at: " + this.executablePath);
                return true;
            } catch (IOException | URISyntaxException | InterruptedException | UnsupportedOperationException e) {
                LOGGER.log(Level.SEVERE, "Failed to provision the Bitwarden executable.", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
    }

    /**
     * Ensures the Bitwarden CLI executable is present, downloading it if it does not exist.
     * <p>
     * This is the primary method for automatic, on-demand setup. It first checks if the
     * executable exists and only performs the expensive download operation if necessary.
     *
     * @return {@code true} if the executable is present or was successfully downloaded, otherwise {@code false}.
     */
    public boolean provisionExecutable() {
        String executableName = getExecutableName();
        File pluginBinDir = getPluginBinDirectory();
        File executableFile = new File(pluginBinDir, executableName);

        if (executableFile.exists()) {
            this.executablePath = executableFile.getAbsolutePath();
            return true;
        }

        return downloadLatestExecutable();
    }

    /**
     * Gets the absolute path to the managed Bitwarden CLI executable.
     * <p>
     * This is the main entry point for getting the path to the CLI. It uses an in-memory cache
     * for performance and is self-healing: if the executable is missing for any reason, it
     * will automatically attempt to provision it on-demand.
     *
     * @return The full path to the {@code bw} executable.
     * @throws IllegalStateException if the executable is not found and cannot be downloaded.
     */
    public String getExecutablePath() {
        String userPath = BitwardenConfig.getInstance().getCliExecutablePath();
        if (userPath != null && !userPath.trim().isEmpty()) {
            File userCli = new File(userPath.trim());
            if (userCli.exists() && userCli.canExecute()) {
                LOGGER.fine(() -> "Using user-configured Bitwarden CLI path: " + userPath);
                return userCli.getAbsolutePath();
            } else {
                LOGGER.warning("User-configured Bitwarden CLI path is invalid (does not exist or is not executable). "
                        + "Falling back to automatic provisioning. Path: " + userPath);
            }
        }
        if (executablePath != null && new File(executablePath).exists()) {
            return executablePath;
        }
        if (provisionExecutable()) {
            return executablePath;
        } else {
            throw new IllegalStateException(
                    "Bitwarden CLI is not installed and could not be downloaded automatically. "
                            + "If on an unsupported architecture, please install it manually and set the path in the Jenkins configuration.");
        }
    }

    /**
     * Gets a dedicated 'bin' directory within the plugin's data directory for the executable.
     *
     * @return A {@link File} handle to the 'bin' directory.
     * @throws RuntimeException if the directory cannot be created.
     */
    private File getPluginBinDirectory() {
        File pluginDir = PluginDirectoryProvider.getPluginDataDirectory();
        File binDir = new File(pluginDir, "bin");
        try {
            Files.createDirectories(binDir.toPath());
            LOGGER.fine("Plugin bin directory is ready: " + binDir.getAbsolutePath());
        } catch (IOException e) {
            String errorMessage = "Could not create plugin bin directory: " + binDir.getAbsolutePath()
                    + "\nDoes Jenkins have proper file permissions?";
            throw new RuntimeException(errorMessage, e);
        }
        return binDir;
    }
}
