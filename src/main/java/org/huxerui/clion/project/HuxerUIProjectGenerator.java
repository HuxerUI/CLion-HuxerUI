package org.huxerui.clion.project;

import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.progress.ProgressIndicator;
import org.huxerui.clion.cli.HuxerUICommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class HuxerUIProjectGenerator {
    private HuxerUIProjectGenerator() {}

    static void CreateWithCli(Path destination, Path home, Path cli, boolean application,
            HuxerUIDirectoryProjectGenerator.Settings settings, ProgressIndicator indicator) throws Exception {
        String name = destination.getFileName().toString();
        List<String> arguments = new ArrayList<>(List.of("create", application ? "app" : "library", name,
                "--build", settings.build_system(), "--agent", "none"));
        if (settings.build_system().equals("cmake")) {
            if (!settings.project_id().isBlank()) {
                arguments.addAll(List.of("--id", settings.project_id()));
            }
            if (!settings.platforms().isEmpty()) {
                arguments.addAll(List.of("--platform", String.join(",", settings.platforms())));
            }
        } else if (application) {
            arguments.addAll(List.of("--template", settings.template()));
        }
        Path staging = Files.createTempDirectory(RequireParent(destination), "." + name + ".huxerui-create-");
        try {
            indicator.setText("Generating HuxerUI project");
            var handler = new CapturingProcessHandler(HuxerUICommand.Create(home, cli, staging, arguments));
            var output = handler.runProcessWithProgressIndicator(indicator, 120_000);
            indicator.checkCanceled();
            HuxerUICommand.RequireSuccess(output);
            Path generated = staging.resolve(name);
            if (!application && settings.build_system().equals("cmake") && settings.platforms().contains("web")) {
                Path sources = generated.resolve("platform/web/src");
                Files.createDirectories(sources);
                Files.writeString(sources.resolve(".gitkeep"), "", StandardOpenOption.CREATE);
            }
            ProjectTreeInstaller.Install(generated, destination);
        } finally {
            ProjectTreeInstaller.DeleteTree(staging);
        }
    }

    private static Path RequireParent(Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("HuxerUI project parent directory does not exist: " + destination);
        }
        return parent;
    }
}
