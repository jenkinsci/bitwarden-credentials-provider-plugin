package com.mwdle.bitwarden;

import com.mwdle.PluginDirectoryProvider;
import hudson.Extension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jenkins.model.Jenkins;

/**
 * Manages the Bitwarden CLI executable lifecycle.
 *
 * <p>Handles OS detection, downloading, extraction, permission setting,
 * and cleanup of the executable on Jenkins shutdown.</p>
 */
@Extension
public final class BitwardenCLIManager {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCLIManager.class.getName());
    private volatile String executablePath;
    private final transient Object provisionLock = new Object();

    /**
     * Provides global access to the single instance of this manager, as managed by Jenkins.
     *
     * @return The singleton instance of {@link BitwardenSessionManager}.
     */
    public static BitwardenCLIManager getInstance() {
        return Jenkins.get().getExtensionList(BitwardenCLIManager.class).get(0);
    }

    /**
     * Represents supported operating systems for the Bitwarden CLI.
     */
    private enum OS {
        WINDOWS,
        MAC,
        LINUX;

        /**
         * Detects the current operating system.
         *
         * @return the detected OS enum
         * @throws UnsupportedOperationException if OS is not supported
         */
        static OS detect() {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) return WINDOWS;
            if (osName.contains("mac")) return MAC;
            if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) return LINUX;
            LOGGER.fine(() -> "Detected OS: " + osName);
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
    }

    private String getDownloadUrl() {
        OS os = OS.detect();
        return switch (os) {
            case WINDOWS -> "https://bitwarden.com/download/?app=cli&platform=windows";
            case MAC -> "https://bitwarden.com/download/?app=cli&platform=macos";
            case LINUX -> "https://bitwarden.com/download/?app=cli&platform=linux";
        };
    }

    private String getExecutableName() {
        OS os = OS.detect();
        return (os == OS.WINDOWS) ? "bw.exe" : "bw";
    }

    /**
     * Downloads the zip archive, extracts the executable, and sets the necessary execute permissions.
     * @param downloadUrl the URL of the zip archive to download
     * @param targetFile the destination file for the extracted executable
     * @throws IOException if the download or extraction fails
     */
    private void downloadAndExtract(URL downloadUrl, File targetFile) throws IOException {
        LOGGER.fine(() -> "Downloading Bitwarden CLI from URL: " + downloadUrl);
        File bwCliZip = File.createTempFile("bw-cli", ".zip");
        try {
            try (InputStream in = downloadUrl.openStream()) {
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
     * Downloads the latest Bitwarden CLI, overwriting any existing version.
     * @return true on success, false on failure.
     */
    public boolean downloadLatestExecutable() {
        synchronized (provisionLock) {
            LOGGER.info("Downloading and provisioning the latest Bitwarden CLI executable...");
            try {
                String downloadUrl = getDownloadUrl();
                String executableName = getExecutableName();
                File pluginBinDir = getPluginBinDirectory();
                File executableFile = new File(pluginBinDir, executableName);

                downloadAndExtract(new URI(downloadUrl).toURL(), executableFile);
                this.executablePath = executableFile.getAbsolutePath();
                LOGGER.info("Successfully provisioned Bitwarden CLI at: " + this.executablePath);
                return true;
            } catch (IOException | URISyntaxException e) {
                LOGGER.log(Level.SEVERE, "Failed to provision the Bitwarden executable.", e);
                return false;
            }
        }
    }

    /**
     * Constructs the singleton BitwardenExecutableManager.
     * Detects the OS, determines the target path, and downloads the executable if it doesn't exist.
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
     *
     * @return The full path to the 'bw' executable.
     */
    public String getExecutablePath() {
        if (executablePath != null && new File(executablePath).exists()) {
            return executablePath;
        }
        if (provisionExecutable()) {
            return executablePath;
        } else {
            throw new IllegalStateException("Bitwarden CLI is not installed and could not be downloaded.");
        }
    }

    /**
     * Gets a dedicated 'bin' directory within this plugin's home folder.
     *
     * @return A file handle to the 'bin' directory.
     */
    private File getPluginBinDirectory() {
        LOGGER.fine("Getting plugin bin directory.");
        File pluginDir = PluginDirectoryProvider.pluginDirectory();
        File binDir = new File(pluginDir, "bin");
        if (!binDir.exists()) {
            if (!binDir.mkdir()) {
                throw new RuntimeException("Could not create plugin bin directory: " + binDir.getAbsolutePath()
                        + "\nDoes Jenkins have proper file permissions?");
            } else {
                LOGGER.fine("Created plugin bin directory: " + binDir.getAbsolutePath());
            }
        } else {
            LOGGER.fine("Plugin bin directory already exists: " + binDir.getAbsolutePath());
        }
        return binDir;
    }
}
