package org.huxerui.clion.preview;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class HuxerUIPreviewBuild {
    private static final String project_name = "huxerui_preview";
    private static final int command_timeout = 15_000;
    private static final int build_timeout = 600_000;

    record Inputs(Path source, String call, long revision) {}

    private record Compilation(Inputs inputs, @Nullable Path executable, @Nullable ExecutionException error) {}

    private final Project project_;
    private Path staging_;
    private Path sdk_;
    private Compilation compilation_;

    HuxerUIPreviewBuild(Project project) {
        project_ = project;
    }

    synchronized @Nullable BufferedImage Render(Inputs inputs, HuxerUIPreviewSize size, boolean source_saved,
            @Nullable HuxerUIPreviewCpp syntax, ProgressIndicator indicator) throws Exception {
        indicator.checkCanceled();
        HuxerUICommand.RequireTrusted(project_);
        if (!Files.isRegularFile(inputs.source())) {
            throw new IllegalStateException("The selected View source is missing. Select the function again.");
        }

        Path sdk = HuxerUIProjectService.Get(project_).SdkHome();
        Path executable = null;
        if (Objects.equals(sdk, sdk_) && compilation_ != null && compilation_.inputs().equals(inputs)) {
            if (compilation_.error() != null) {
                throw compilation_.error();
            }
            var cached = compilation_.executable();
            if (cached != null && Files.isRegularFile(cached)) {
                executable = cached;
            }
        }
        if (executable == null) {
            if (!source_saved) {
                return null;
            }
            Path cli = sdk == null ? null : SdkLayout.FindCli(sdk);
            if (cli == null) {
                throw new IllegalStateException("Select a HuxerUI SDK with a built CLI to preview this project.");
            }
            Path root = HuxerUICommand.ProjectRoot(project_);
            String platform = HuxerUIProjectService.HostPlatformId();
            Path app = Prepare(root, sdk, cli, platform, indicator);
            executable = Compile(app, root, sdk, cli, platform, inputs, syntax, indicator);
        }
        return CaptureFrame(executable, size, indicator);
    }

    private Path Compile(Path app, Path root, Path sdk, Path cli, String platform, Inputs inputs,
            @Nullable HuxerUIPreviewCpp syntax, ProgressIndicator indicator) throws Exception {
        compilation_ = null;
        HuxerUIPreviewSources.Write(app, root, inputs.source(), inputs.call(), syntax, indicator);
        indicator.setText("Building the HuxerUI preview");
        try {
            Run(HuxerUICommand.Create(sdk, cli, app, List.of("build", platform, "--config", "debug")),
                    indicator, build_timeout);
        } catch (ExecutionException error) {
            compilation_ = new Compilation(inputs, null, error);
            throw error;
        }
        Path executable = Path.of(Files.readString(app.resolve("preview-Debug.txt")).strip());
        compilation_ = new Compilation(inputs, executable, null);
        return executable;
    }

    private BufferedImage CaptureFrame(Path executable, HuxerUIPreviewSize size, ProgressIndicator indicator)
            throws IOException, ExecutionException {
        Path frame = staging_.resolve("frame.png");
        Files.deleteIfExists(frame);
        indicator.setText("Capturing the native preview frame");
        Run(HuxerUICommand.LocalCommand(executable.toString(), staging_.resolve(project_name), List.of())
                .withEnvironment("HUXERUI_PREVIEW_PNG", frame.toString())
                .withEnvironment("HUXERUI_PREVIEW_WIDTH", Integer.toString(size.width()))
                .withEnvironment("HUXERUI_PREVIEW_HEIGHT", Integer.toString(size.height())), indicator, command_timeout);
        if (!Files.isRegularFile(frame)) {
            throw new IOException("The preview app exited before producing a frame.");
        }
        BufferedImage image = ImageIO.read(frame.toFile());
        if (image == null) {
            throw new IOException("The preview app produced an invalid image.");
        }
        return image;
    }

    private Path Prepare(Path root, Path sdk, Path cli, String platform, ProgressIndicator indicator) throws Exception {
        if (staging_ != null && sdk.equals(sdk_)) {
            return staging_.resolve(project_name);
        }
        Close();
        indicator.setText("Preparing the HuxerUI preview app");
        Path staging = Files.createTempDirectory("huxerui-preview-");
        String app_id = "org.huxerui.preview.p" + UUID.randomUUID().toString().replace("-", "");
        try {
            Run(HuxerUICommand.Create(sdk, cli, staging, List.of("create", "app", project_name,
                    "--build-system", "cmake", "--platform", platform, "--id", app_id, "--agent", "none", "--yes")),
                    indicator, command_timeout);
            Path app = staging.resolve(project_name);
            WriteResource(app.resolve("CMakeLists.txt"), "CMakeLists.txt");
            WriteResource(app.resolve("src/app.cpp"), "app.cpp");
            Files.writeString(app.resolve("project-path.cmake"),
                    "set(HUXERUI_PREVIEW_PROJECT_DIR "
                            + HuxerUIPreviewSources.CMakePath(root) + ")\n"
                            + "set(HUXERUI_PREVIEW_APP_ID " + app_id + ")\n");
            switch (platform) {
                case "macos" -> WriteResource(app.resolve("capture-macos.mm"), "capture-macos.mm");
                case "linux", "windows" -> WriteResource(app.resolve("capture-" + platform + ".cpp"),
                        "capture-" + platform + ".cpp");
                default -> throw new IllegalArgumentException("Unsupported HuxerUI preview platform: " + platform);
            }
            staging_ = staging;
            sdk_ = sdk;
            return app;
        } finally {
            if (staging_ == null) {
                FileUtil.delete(staging.toFile());
            }
        }
    }

    private static void WriteResource(Path target, String name) throws IOException {
        Files.writeString(target, ReadResource(name));
    }

    private static String ReadResource(String name) throws IOException {
        try (var input = Objects.requireNonNull(HuxerUIPreviewBuild.class.getResourceAsStream("/preview/" + name), name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void Run(GeneralCommandLine command, ProgressIndicator indicator, int timeout)
            throws ExecutionException {
        indicator.checkCanceled();
        ProcessOutput output = new CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, timeout);
        indicator.checkCanceled();
        if (output.isTimeout() || output.getExitCode() != 0) {
            String detail = output.isTimeout() ? "The command timed out." : output.getStdout().strip();
            if (!output.getStderr().isBlank() && !output.isTimeout()) {
                detail = (detail + "\n" + output.getStderr()).strip();
            }
            throw new ExecutionException(command.getCommandLineString() + "\n" + detail);
        }
    }

    synchronized void Close() {
        if (staging_ != null) {
            FileUtil.delete(staging_.toFile());
            staging_ = null;
            compilation_ = null;
        }
    }
}
