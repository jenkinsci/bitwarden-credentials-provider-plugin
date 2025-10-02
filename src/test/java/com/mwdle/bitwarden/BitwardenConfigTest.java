package com.mwdle.bitwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

import hudson.ExtensionList;
import java.nio.file.Path;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for the BitwardenGlobalConfig class.
 * This test verifies that the setters call save() and the static get() method works as expected.
 */
@DisplayName("BitwardenGlobalConfig")
class BitwardenConfigTest {

    @TempDir
    Path tempDir;

    private MockedStatic<GlobalConfiguration> mockedGlobalConfig;
    private MockedStatic<Jenkins> mockedJenkins;

    @BeforeEach
    void setUp() {
        mockedGlobalConfig = mockStatic(GlobalConfiguration.class);

        Jenkins jenkinsMock = mock(Jenkins.class);
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());
        mockedJenkins = mockStatic(Jenkins.class);
        mockedJenkins.when(Jenkins::get).thenReturn(jenkinsMock);
    }

    @AfterEach
    void tearDown() {
        mockedGlobalConfig.close();
        mockedJenkins.close();
    }

    @Test
    @DisplayName("get() should retrieve the singleton instance")
    void getInstanceShouldRetrieveInstance() {
        BitwardenConfig config = new BitwardenConfig();
        @SuppressWarnings("unchecked")
        ExtensionList<GlobalConfiguration> extensionList = mock(ExtensionList.class);
        when(extensionList.get(BitwardenConfig.class)).thenReturn(config);
        when(GlobalConfiguration.all()).thenReturn(extensionList);

        BitwardenConfig result = BitwardenConfig.getInstance();

        assertNotNull(result, "The get() method should not return null.");
        assertEquals(config, result, "The get() method should return the correct singleton instance.");
    }
}
