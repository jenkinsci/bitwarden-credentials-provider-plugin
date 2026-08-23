package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying that the plugin configuration can be successfully
 * provisioned and exported via Jenkins Configuration as Code (JCasC).
 */
@WithJenkinsConfiguredWithCode
@DisplayName("BitwardenConfig JCasC")
class BitwardenConfigCascTest {

    @Test
    @ConfiguredWithCode("casc.yaml")
    @DisplayName("should populate BitwardenConfig directly from YAML")
    void shouldPopulateFromYaml(JenkinsConfiguredWithCodeRule ignored) {
        BitwardenConfig config = BitwardenConfig.getInstance();

        assertEquals("https://vault.example.com", config.getServerUrl());
        assertEquals("bitwarden-api-key", config.getApiCredentialId());
        assertEquals("bitwarden-master-password", config.getMasterPasswordCredentialId());
        assertEquals("/usr/local/bin/bw", config.getCliExecutablePath());
        assertEquals(15, config.getCacheDuration());
        assertEquals(".env, .properties", config.getFileCredentialSuffixes());
    }

    @Test
    @ConfiguredWithCode("casc.yaml")
    @DisplayName("should export BitwardenConfig back to YAML correctly")
    void shouldExportToYaml(JenkinsConfiguredWithCodeRule ignored) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String exportedYaml = out.toString(StandardCharsets.UTF_8);

        assertTrue(exportedYaml.contains("serverUrl: \"https://vault.example.com\""));
        assertTrue(exportedYaml.contains("apiCredentialId: \"bitwarden-api-key\""));
        assertTrue(exportedYaml.contains("masterPasswordCredentialId: \"bitwarden-master-password\""));
        assertTrue(exportedYaml.contains("cliExecutablePath: \"/usr/local/bin/bw\""));
        assertTrue(exportedYaml.contains("cacheDuration: 15"));
        assertTrue(exportedYaml.contains("fileCredentialSuffixes: \".env, .properties\""));
    }
}
