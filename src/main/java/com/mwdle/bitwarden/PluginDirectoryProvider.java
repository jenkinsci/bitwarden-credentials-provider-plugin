package com.mwdle.bitwarden;

import java.io.File;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * A thread-safe utility class for providing a stable, dedicated data directory for the plugin.
 * <p>
 * This class ensures that all plugin data (such as the downloaded CLI, cache files, and CLI
 * configuration) is stored in a consistent location within the Jenkins home directory. It uses a
 * double-checked locking pattern to efficiently find and create this directory only once.
 */
public final class PluginDirectoryProvider {

    private static final String PLUGIN_DIR_NAME = "bitwarden-credentials-provider-data";
    private static final Logger LOGGER = Logger.getLogger(PluginDirectoryProvider.class.getName());
    private static volatile File pluginDirectory;
    private static final Object lock = new Object();

    /**
     * A private constructor to prevent instantiation of this utility class.
     */
    private PluginDirectoryProvider() {}

    /**
     * Gets the single, stable data directory for this plugin.
     * <p>
     * On the first call, this method will find or create the directory
     * {@code $JENKINS_HOME/bitwarden-credentials-provider-data} and cache the path for all
     * subsequent calls. The lookup and creation are performed in a thread-safe manner.
     *
     * @return The {@link File} object representing the plugin's data directory.
     * @throws RuntimeException if the directory cannot be created, which may indicate a file permissions issue.
     */
    public static File getPluginDataDirectory() {
        File result = pluginDirectory;
        if (result != null) {
            return result;
        }
        synchronized (lock) {
            // This prevents a second thread from re-doing the work if it was waiting for the lock.
            if (pluginDirectory != null) {
                return pluginDirectory;
            }
            File dir = new File(Jenkins.get().getRootDir(), PLUGIN_DIR_NAME);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    String errorMessage = "Could not create plugin directory: " + dir.getAbsolutePath()
                            + "\nDoes Jenkins have proper file permissions?";
                    throw new RuntimeException(errorMessage);
                } else {
                    LOGGER.fine("Created plugin data directory: " + dir.getAbsolutePath());
                }
            }
            pluginDirectory = dir;
            return dir;
        }
    }
}
