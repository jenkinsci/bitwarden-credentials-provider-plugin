package com.mwdle.bitwarden.cli;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import jenkins.model.Jenkins;

/**
 * A utility that provides dedicated filesystem directory paths for this plugin's Bitwarden CLI data.
 */
public final class BitwardenDirectoryProvider {

    private static volatile File pluginDirectory;
    private static volatile File binDirectory;
    private static volatile File cliDataDirectory;

    private BitwardenDirectoryProvider() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return this plugin's Bitwarden CLI data directory, creating it if necessary and caching it for subsequent calls
     * @throws IOException if the parent directory cannot be created
     */
    @NonNull
    public static File getCliDataDirectory() throws IOException {
        if (cliDataDirectory == null) {
            File directory = new File(getPluginDirectory(), "bwcli");
            Files.createDirectories(directory.toPath());
            cliDataDirectory = directory;
        }
        return cliDataDirectory;
    }

    /**
     * @return this plugin's Bitwarden CLI {@code bin} directory, creating it if necessary and caching it for subsequent calls
     * @throws IOException if the directory or parent directory cannot be created
     */
    @NonNull
    public static File getBinDirectory() throws IOException {
        if (binDirectory == null) {
            File directory = new File(getPluginDirectory(), "bin");
            Files.createDirectories(directory.toPath());
            binDirectory = directory;
        }
        return binDirectory;
    }

    /**
     * @return this plugin's data directory, creating it if necessary and caching it for subsequent calls
     * @throws IOException if the directory cannot be created
     */
    @NonNull
    private static File getPluginDirectory() throws IOException {
        if (pluginDirectory == null) {
            File directory = new File(Jenkins.get().getRootDir(), "bitwarden-credentials-provider-data");
            Files.createDirectories(directory.toPath());
            pluginDirectory = directory;
        }
        return pluginDirectory;
    }
}
