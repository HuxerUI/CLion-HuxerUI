package org.huxerui.clion.cli;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.project.Project;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HuxerUICommand {
    private HuxerUICommand() {}

    public static GeneralCommandLine Create(Project project, Path directory, List<String> arguments) {
        RequireTrusted(project);
        Path home = HuxerUIProjectService.Get(project).SdkHome();
        if (home == null) {
            throw new IllegalStateException("Select a HuxerUI SDK from Tools | HuxerUI.");
        }
        Path cli = SdkLayout.FindCli(home);
        if (cli == null) {
            throw new IllegalStateException("Build the HuxerUI CLI or select an installed SDK.");
        }
        return Create(home, cli, directory, arguments);
    }

    public static GeneralCommandLine Create(Path home, Path cli, Path directory, List<String> arguments) {
        var command = LocalCommand(cli.toString(), directory, arguments).withEnvironment("HUXERUI_HOME", home.toString());
        switch (arguments.getFirst()) {
            case "build", "run", "package", "doctor", "setup", "open" -> command.addParameters("--sdk", home.toString());
        }
        return command;
    }

    public static GeneralCommandLine LocalCommand(String executable, Path directory, List<String> arguments) {
        return new GeneralCommandLine(executable).withWorkDirectory(directory.toFile()).withParameters(arguments)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
    }

    public static GeneralCommandLine Mcpp(Path directory, String operation, String configuration) {
        return LocalCommand("mcpp", directory, "release".equals(configuration)
                ? List.of(operation, "--release") : List.of(operation));
    }

    public static ProcessOutput Capture(Path home, Path cli, Path directory, List<String> arguments) throws Exception {
        return RequireSuccess(new CapturingProcessHandler(Create(home, cli, directory, arguments)).runProcess(120_000));
    }

    public static ProcessOutput RequireSuccess(ProcessOutput output) {
        if (output.isCancelled()) {
            throw new com.intellij.openapi.progress.ProcessCanceledException();
        }
        if (output.isTimeout()) {
            throw new IllegalStateException("HuxerUI command timed out");
        }
        if (output.getExitCode() != 0) {
            String message = output.getStderr().isBlank() ? output.getStdout() : output.getStderr();
            throw new IllegalStateException(message.strip());
        }
        return output;
    }

    public static void RequireTrusted(Project project) {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw new IllegalStateException("Trust this project in CLion before running HuxerUI tools.");
        }
    }

    public static List<String> BuildArguments(String operation, String platforms, String configuration) {
        return new ArrayList<>(List.of(operation, platforms, "--config", configuration));
    }

    public static Path ProjectRoot(Project project) {
        String base_path = project.getBasePath();
        if (base_path == null) {
            throw new IllegalStateException("The project has no local base directory");
        }
        return Path.of(base_path);
    }
}
