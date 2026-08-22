package com.mwdle.bitwarden.cli;

import static com.mwdle.bitwarden.cli.BitwardenDirectoryProvider.getBinDirectory;

import com.mwdle.bitwarden.BitwardenConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Functions;
import hudson.ProxyConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A utility for managing this plugin's Bitwarden CLI executable.
 */
public final class BitwardenCliManager {

    private static final Logger LOGGER = Logger.getLogger(BitwardenCliManager.class.getName());
    private static final Object LOCK = new Object();

    private BitwardenCliManager() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the absolute path to the Bitwarden CLI executable
     * @throws InterruptedException if the automatic Bitwarden CLI provisioning is interrupted
     * @throws IOException if the executable is not found and cannot be automatically downloaded
     */
    @NonNull
    public static String getExecutablePath() throws InterruptedException, IOException {
        String userProvidedPath = BitwardenConfig.getInstance().getCliExecutablePath();
        if (userProvidedPath != null) {
            return userProvidedPath;
        }
        File executable = new File(getBinDirectory(), getExecutableName());
        if (!executable.isFile()) {
            synchronized (LOCK) {
                if (!executable.isFile()) {
                    updateExecutable();
                }
            }
        }
        return executable.getAbsolutePath();
    }

    /**
     * Downloads and provisions the latest native Bitwarden CLI executable, safely replacing any existing version.
     *
     * @throws InterruptedException if the update is interrupted
     * @throws IOException if the installation fails
     */
    public static void updateExecutable() throws InterruptedException, IOException {
        synchronized (LOCK) {
            LOGGER.info("Provisioning the Bitwarden CLI executable");
            File executableZip = Files.createTempFile(getBinDirectory().toPath(), getExecutableName(), ".zip")
                    .toFile();
            try {
                // Update this to try-with-resources once plugin is updated to a Java 21+ Jenkins baseline.
                @SuppressWarnings("java:S2095") // HttpClient does not implement AutoCloseable in Java 17
                HttpClient client = ProxyConfiguration.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder(getDownloadUrl()).build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("Failed to download Bitwarden CLI executable! HTTP status code: %s"
                                .formatted(response.statusCode()));
                    }
                    Files.copy(in, executableZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                File executable = new File(getBinDirectory(), getExecutableName());
                File executableTemp = Files.createTempFile(getBinDirectory().toPath(), getExecutableName(), ".tmp")
                        .toFile();
                try {
                    try (ZipFile zipFile = new ZipFile(executableZip)) {
                        ZipEntry executableEntry = zipFile.getEntry(getExecutableName());
                        if (executableEntry == null) {
                            throw new IOException(
                                    "Could not find the 'bw' or 'bw.exe' executable in the downloaded archive");
                        }
                        try (InputStream zipInputStream = zipFile.getInputStream(executableEntry)) {
                            Files.copy(zipInputStream, executableTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    if (!executableTemp.setExecutable(true, true)) {
                        throw new IOException(
                                "Failed to set execute permission on downloaded Bitwarden CLI executable %s"
                                        .formatted(executableTemp.getAbsolutePath()));
                    }
                    Files.move(
                            executableTemp.toPath(),
                            executable.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.log(Level.INFO, "Provisioned Bitwarden CLI executable: {0}", executable.getAbsolutePath());
                } finally {
                    Files.deleteIfExists(executableTemp.toPath());
                }
            } finally {
                Files.deleteIfExists(executableZip.toPath());
            }
        }
    }

    /**
     * Returns the download URL for the Bitwarden CLI based on the current OS and architecture.
     *
     * @return the appropriate download URL for the Bitwarden CLI zip archive.
     * @throws IOException if automatic download is not possible for the current OS or architecture.
     */
    @NonNull
    private static URI getDownloadUrl() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (!"amd64".equals(arch) && !"x86_64".equals(arch)) {
            throw new IOException("Automatic CLI download not possible for architecture: %s. See plugin documentation"
                    .formatted(arch));
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return URI.create("https://bitwarden.com/download/?app=cli&platform=windows");
        if (os.contains("mac")) return URI.create("https://bitwarden.com/download/?app=cli&platform=macos");
        if (os.contains("linux")) return URI.create("https://bitwarden.com/download/?app=cli&platform=linux");
        throw new IOException("Automatic CLI download not possible for OS: %s. See plugin documentation".formatted(os));
    }

    /**
     * Determines the name for the Bitwarden CLI executable file based on the current OS.
     *
     * @return the name of the executable ({@code bw.exe} or {@code bw})
     */
    @NonNull
    private static String getExecutableName() {
        return Functions.isWindows() ? "bw.exe" : "bw";
    }
}
