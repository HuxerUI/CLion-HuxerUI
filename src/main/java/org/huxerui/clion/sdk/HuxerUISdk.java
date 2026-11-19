package org.huxerui.clion.sdk;

import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.progress.ProgressIndicator;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public record HuxerUISdk(Path home, Path cli, String version) {
    private static final String cli_version_prefix = "huxerui ";
    private static final Pattern version_pattern = Pattern.compile("(\\d+)\\.(\\d+)\\.\\d+(?:[-+][\\w.-]+)?");

    public static final class UnsupportedVersionException extends IOException {
        private UnsupportedVersionException(String version) {
            super("HuxerUI " + version + " found. Add SDK 0.5.0 or newer.");
        }
    }

    public static HuxerUISdk Inspect(Path directory, @Nullable ProgressIndicator indicator) throws Exception {
        Path home = directory.toRealPath();
        Path cli = SdkLayout.IsValid(home) ? SdkLayout.FindCli(home) : null;
        if (cli == null || !Files.isExecutable(cli)) {
            throw new IOException("The directory does not contain a usable HuxerUI CLI: " + home);
        }
        return new HuxerUISdk(home, cli, ReadVersion(cli, home, indicator));
    }

    static String ReadVersion(Path cli, Path home, @Nullable ProgressIndicator indicator) throws Exception {
        var handler = new CapturingProcessHandler(HuxerUICommand.Create(home, cli, home, List.of("--version")));
        var output = indicator == null ? handler.runProcess(4_000)
                : handler.runProcessWithProgressIndicator(indicator, 4_000);
        String text = HuxerUICommand.RequireSuccess(output).getStdout().strip();
        String version = text.startsWith(cli_version_prefix) ? text.substring(cli_version_prefix.length()) : "";
        if (!version_pattern.matcher(version).matches()) {
            throw new IOException("Could not read the HuxerUI CLI version: " + cli);
        }
        if (!SupportsVersion(version)) {
            throw new UnsupportedVersionException(version);
        }
        return version;
    }

    static boolean SupportsVersion(String version) {
        var match = version_pattern.matcher(version);
        return match.matches() && (Integer.parseInt(match.group(1)) > 0 || Integer.parseInt(match.group(2)) >= 5);
    }
}
