package com.mwdle;

import hudson.PluginWrapper;
import java.io.File;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 *
 */
public final class PluginDirectoryProvider {

    private static final Logger LOGGER = Logger.getLogger(PluginDirectoryProvider.class.getName());
    private static volatile File pluginDirectory;
    private static final Object lock = new Object();

    /**
     *
     */
    public static File pluginDirectory() {
        File result = pluginDirectory;
        if (result != null) {
            return result;
        }
        synchronized (lock) {
            // This prevents a second thread from re-doing the work if it was waiting for the lock.
            if (pluginDirectory != null) {
                return pluginDirectory;
            }
            PluginWrapper plugin = Jenkins.get().getPluginManager().whichPlugin(BitwardenCredentialsProvider.class);
            if (plugin == null) {
                throw new IllegalStateException("Could not find the wrapper for the Bitwarden plugin.");
            }
            File pluginsDir = new File(Jenkins.get().getRootDir(), "plugins");
            File dir = new File(pluginsDir, plugin.getShortName());
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    String errorMessage = "Could not create plugin directory: " + dir.getAbsolutePath()
                            + "\nDoes Jenkins have proper file permissions?";
                    throw new RuntimeException(errorMessage);
                } else {
                    LOGGER.fine("Created plugin bin directory: " + dir.getAbsolutePath());
                }
            }
            pluginDirectory = dir;
            return dir;
        }
    }
}
