package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@DisplayName("DirectoryProvider")
class DirectoryProviderTest {

    @Test
    @DisplayName("creates and caches CLI data and bin directories relative to Jenkins root")
    void createsAndCachesDirectories(JenkinsRule ignored) throws Exception {
        File cliDir1 = DirectoryProvider.getCliDataDirectory();
        File cliDir2 = DirectoryProvider.getCliDataDirectory();

        assertTrue(cliDir1.exists());
        assertTrue(cliDir1.isDirectory());
        assertEquals(new File(Jenkins.get().getRootDir(), "bitwarden-credentials-provider-data/bwcli"), cliDir1);
        assertEquals(cliDir1, cliDir2, "subsequent calls must return the cached instance");

        File binDir1 = DirectoryProvider.getBinDirectory();
        File binDir2 = DirectoryProvider.getBinDirectory();

        assertTrue(binDir1.exists());
        assertTrue(binDir1.isDirectory());
        assertEquals(new File(Jenkins.get().getRootDir(), "bitwarden-credentials-provider-data/bin"), binDir1);
        assertEquals(binDir1, binDir2, "subsequent calls must return the cached instance");
    }
}
