package com.mwdle.bitwarden.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Verifies the shared command-execution contract of {@link BitwardenCli}: successful output is returned stripped, a
 * non-zero exit surfaces the stderr in an {@link IOException}, and the isolated CLI data directory is always exported.
 * A single representative command ({@code version()}) exercises the private {@code executeCommand} helper; per-command
 * behavior is validated by the model and integration tests rather than duplicated here.
 */
@DisplayName("BitwardenCli command execution")
class BitwardenCliTest {

    private static final String EXECUTABLE = "/fake/path/bw";

    @TempDir
    Path tempDir;

    private MockedStatic<CliManager> cliManager;
    private MockedStatic<DirectoryProvider> directoryProvider;
    private MockedConstruction<Launcher.LocalLauncher> launcher;

    private Map<String, String> capturedEnvs;
    private ArgumentListBuilder capturedCommand;
    private OutputStream capturedStdout;
    private OutputStream capturedStderr;

    private String stdout = "";
    private String stderr = "";
    private int exitCode = 0;

    @BeforeEach
    void setUp() throws Exception {
        cliManager = mockStatic(CliManager.class);
        cliManager.when(CliManager::getExecutablePath).thenReturn(EXECUTABLE);

        directoryProvider = mockStatic(DirectoryProvider.class);
        directoryProvider.when(DirectoryProvider::getCliDataDirectory).thenReturn(tempDir.toFile());

        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        when(procStarter.cmds(any(ArgumentListBuilder.class))).thenAnswer(invocation -> {
            capturedCommand = invocation.getArgument(0);
            return procStarter;
        });
        when(procStarter.envs(anyMap())).thenAnswer(invocation -> {
            capturedEnvs = invocation.getArgument(0);
            return procStarter;
        });
        when(procStarter.stdout(any(OutputStream.class))).thenAnswer(invocation -> {
            capturedStdout = invocation.getArgument(0);
            return procStarter;
        });
        when(procStarter.stderr(any(OutputStream.class))).thenAnswer(invocation -> {
            capturedStderr = invocation.getArgument(0);
            return procStarter;
        });

        Proc proc = mock(Proc.class);
        when(procStarter.start()).thenReturn(proc);
        when(proc.joinWithTimeout(anyLong(), any(TimeUnit.class), any(TaskListener.class)))
                .thenAnswer(invocation -> {
                    capturedStdout.write(stdout.getBytes(StandardCharsets.UTF_8));
                    capturedStderr.write(stderr.getBytes(StandardCharsets.UTF_8));
                    return exitCode;
                });

        launcher = mockConstruction(
                Launcher.LocalLauncher.class,
                (mock, context) -> when(mock.launch()).thenReturn(procStarter));
    }

    @AfterEach
    void tearDown() {
        launcher.close();
        directoryProvider.close();
        cliManager.close();
    }

    @Test
    @DisplayName("returns stripped stdout and exports the isolated data directory on success")
    void returnsStrippedStdout() throws Exception {
        stdout = "  2026.7.0 \n";
        exitCode = 0;

        String version = BitwardenCli.version();

        assertEquals("2026.7.0", version);
        assertEquals(List.of(EXECUTABLE, "--nointeraction", "--raw", "--version"), capturedCommand.toList());
        assertEquals(tempDir.toFile().getAbsolutePath(), capturedEnvs.get("BITWARDENCLI_APPDATA_DIR"));
    }

    @Test
    @DisplayName("throws an IOException carrying the exit code and stderr on failure")
    void throwsOnNonZeroExit() {
        stdout = "";
        stderr = "boom: something broke";
        exitCode = 1;

        IOException exception = assertThrows(IOException.class, BitwardenCli::version);

        assertTrue(exception.getMessage().contains("exit code 1"));
        assertTrue(exception.getMessage().contains("boom: something broke"));
    }

    @Test
    @DisplayName("exports the isolated data directory even when the command fails")
    void exportsDataDirOnFailure() {
        exitCode = 1;

        assertThrows(IOException.class, BitwardenCli::version);

        assertEquals(tempDir.toFile().getAbsolutePath(), capturedEnvs.get("BITWARDENCLI_APPDATA_DIR"));
    }

    @Test
    @DisplayName("ignores stderr noise if the exit code is zero (e.g. CLI attachment decryption warnings)")
    void ignoresStderrOnSuccess() throws Exception {
        stdout = "  2026.7.0 \n";
        stderr = "ERROR bitwarden_crypto: The decryption operation failed\n[Attachment] Error decrypting attachment";
        exitCode = 0;

        String result = BitwardenCli.version();

        assertEquals("2026.7.0", result);
    }
}
