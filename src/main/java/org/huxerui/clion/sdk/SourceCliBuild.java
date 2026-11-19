package org.huxerui.clion.sdk;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.NioFiles;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.build.HuxerUIBuildTask;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class SourceCliBuild {
    private SourceCliBuild() {}

    static void Queue(Project project, Path home) {
        new Task.Backgroundable(project, "Build HuxerUI Source CLI", true) {
            private HuxerUISdk built_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                var service = HuxerUIProjectService.Get(project);
                if (!service.TryStartTask()) {
                    HuxerUINotifications.error(project, "Build HuxerUI Source CLI", "Stop the active HuxerUI session first.");
                    return;
                }
                try {
                    HuxerUICommand.RequireTrusted(project);
                    built_ = Build(home, project, indicator);
                } catch (ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    HuxerUINotifications.error(project, "HuxerUI CLI build failed", String.valueOf(error.getMessage()));
                } finally {
                    service.FinishTask();
                }
            }

            @Override
            public void onSuccess() {
                if (built_ != null && !project.isDisposed()) {
                    String home = built_.home().toString();
                    HuxerUISdkActionGroup.Apply(project, home);
                    HuxerUINotifications.info(project, "HuxerUI source CLI ready", home);
                }
            }
        }.queue();
    }

    public static HuxerUISdk BuildForWizard(Path home, ProgressIndicator indicator) throws Exception {
        return Build(home, null, indicator);
    }

    private static @Nullable HuxerUISdk Build(Path directory, @Nullable Project project, ProgressIndicator indicator)
            throws Exception {
        Path home = directory.toRealPath();
        if (!SdkLayout.IsSource(home)) {
            throw new IOException("Select the root of a HuxerUI source checkout");
        }
        Path destination = SdkLayout.CachedSourceCli(home);
        Files.createDirectories(destination.getParent());
        Path build = Files.createTempDirectory(destination.getParent(), ".build-");
        try {
            if (!Execute(project, "Configure HuxerUI Source CLI", home, List.of("-S", "tools/huxerui_cli",
                    "-B", build.toString(), "-DCMAKE_BUILD_TYPE=Release"), indicator)) {
                return null;
            }
            if (!Execute(project, "Build HuxerUI Source CLI", home, List.of("--build", build.toString(),
                    "--config", "Release", "--target", "huxerui_cli", "--parallel", "4"), indicator)) {
                return null;
            }
            Path cli = build.resolve(SdkLayout.CliName());
            if (!Files.isRegularFile(cli)) {
                cli = build.resolve("Release").resolve(SdkLayout.CliName());
            }
            String version = HuxerUISdk.ReadVersion(cli, home, indicator);
            indicator.checkCanceled();
            Files.move(cli, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new HuxerUISdk(home, destination, version);
        } finally {
            try {
                NioFiles.deleteRecursively(build);
            } catch (IOException error) {
                Logger.getInstance(SourceCliBuild.class).warn(error);
            }
        }
    }

    private static boolean Execute(@Nullable Project project, String title, Path home, List<String> arguments,
            ProgressIndicator indicator) throws Exception {
        GeneralCommandLine command = HuxerUICommand.LocalCommand("cmake", home, arguments);
        if (project != null) {
            return HuxerUIBuildTask.Execute(project, title, home, () -> command, indicator);
        }
        indicator.setText(title);
        var handler = new CapturingProcessHandler(command);
        HuxerUICommand.RequireSuccess(handler.runProcessWithProgressIndicator(indicator, 300_000));
        return true;
    }
}
