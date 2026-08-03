package com.mwdle.bitwarden;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import jenkins.model.Jenkins;

/**
 * A singleton utility class that provides a stable, dedicated data directory for this plugin.
 * <p>
 * This class ensures a consistent location within the Jenkins home directory for all plugin data,
 * safely lazy-initializing the directory on first use.
 */
public final class PluginDirectoryProvider {

    private static final String PLUGIN_DIR_NAME = "bitwarden-credentials-provider-data";
    private static final Object LOCK = new Object();
    private static volatile File pluginDirectory;

    /**
     * A private constructor to prevent instantiation of this utility class.
     */
    private PluginDirectoryProvider() {}

    /**
     * Returns the stable data directory for this plugin.
     * <p>
     * On the first call, this method will find or create the directory
     * {@code $JENKINS_HOME/bitwarden-credentials-provider-data} and cache the {@link File} handle for all
     * subsequent calls. The lookup and creation are performed in a thread-safe manner.
     *
     * @return the {@link File} object representing this plugin's data directory
     * @throws IllegalStateException if the directory cannot be created, which may indicate a file permissions issue
     */
    public static File getPluginDataDirectory() {
        if (pluginDirectory == null) {
            synchronized (LOCK) {
                // Double-checked locking pattern ensures the directory creation step only occurs once
                if (pluginDirectory == null) {
                    File dir = new File(Jenkins.get().getRootDir(), PLUGIN_DIR_NAME);
                    try {
                        Files.createDirectories(dir.toPath());
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                String.format(
                                        "Failed to create plugin directory: '%s'! Does Jenkins have proper file permissions?",
                                        dir.getAbsolutePath()),
                                e);
                    }
                    pluginDirectory = dir;
                }
            }
        }
        return pluginDirectory;
    }
}
