package org.huxerui.clion.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import org.huxerui.clion.build.HuxerUIBuildTask;
import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.huxerui.clion.cli.HuxerUICommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

final class AndroidDebugSession implements AutoCloseable {
    private static final Pattern traced_process = Pattern.compile("(?m)^TracerPid:\\s*[1-9][0-9]*$");
    private static final int command_timeout = 10_000;

    private final Path root_;
    private final Path adb_;
    private final String device_;
    private final String package_id_;
    private final Path symbols_;
    private final Path compiler_root_;
    private final Path work_;
    private final String socket_ = "huxerui-" + UUID.randomUUID();
    private final String forward_target_ = "localabstract:" + socket_;
    private final String server_path_ = "code_cache/" + socket_;
    private final StringBuffer server_output_ = new StringBuffer();
    private OSProcessHandler server_;
    private boolean forward_requested_;
    private String port_;

    private AndroidDebugSession(Path root, Path adb, String device, String package_id, Path symbols)
            throws IOException {
        root_ = root;
        adb_ = adb;
        device_ = device;
        package_id_ = package_id;
        symbols_ = symbols;
        compiler_root_ = FindCompilerRoot(root, symbols);
        work_ = Files.createTempDirectory("huxerui-android-debug-");
    }

    static AndroidDebugSession Prepare(Project project, HuxerUIRunConfiguration configuration, Path root,
            ProgressIndicator indicator) throws ExecutionException, IOException {
        HuxerUICommand.RequireTrusted(project);
        var doctor = HuxerUICommand.Create(project, root,
                List.of("doctor", "android", "--operation", "run", "--json"));
        var output = new CapturingProcessHandler(doctor).runProcessWithProgressIndicator(indicator, command_timeout);
        indicator.checkCanceled();
        JsonObject checks = JsonParser.parseString(output.getStdout()).getAsJsonObject();
        Path adb = AndroidDeviceAbis.FindTool(checks, "android.platform_tools");
        Path toolchain = AndroidDeviceAbis.FindTool(checks, "android.android_ndk");
        if (adb == null || toolchain == null) {
            throw new ExecutionException("Android debugging requires ADB and the project's Android NDK.");
        }
        String device_id = configuration.GetDeviceId();
        var device = new HuxerUIDevice("android", device_id, configuration.getName(), "device",
                AndroidDeviceAbis.ReadAbis(adb, root, device_id));
        String abi = HuxerUIRunConfiguration.PreferredAndroidAbi(configuration.BuildOptions(), device);
        Path lldb_server = FindLldbServer(toolchain.getParent().getParent().getParent(), abi);
        var options = new HuxerUIBuildOptions("debug", abi, "default", "off");
        var arguments = HuxerUIRunConfiguration.BuildRunArguments("android", device_id, options, false);
        if (!HuxerUIBuildTask.Execute(project, "Build and Deploy Android Debug App", root,
                () -> HuxerUICommand.Create(project, root, arguments), indicator)) {
            throw new ExecutionException("The Android build or deployment failed. See the Build window.");
        }

        Path build = root.resolve("platform/android/app/build");
        String package_id = ReadJson(build.resolve("outputs/apk/debug/output-metadata.json"))
                .get("applicationId").getAsString();
        if (!package_id.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new ExecutionException("The Debug APK has an invalid application ID.");
        }
        JsonObject plan = ReadJson(build.resolve("generated/huxerui/integration/debug/" + abi + "/app.json"));
        Path symbols = Path.of(plan.get("artifact").getAsString());
        if (plan.get("schema").getAsInt() != 1 || !symbols.isAbsolute()
                || !Files.isRegularFile(symbols) || !symbols.getFileName().toString().endsWith(".so")) {
            throw new ExecutionException("The unstripped Android application library is missing: " + symbols);
        }

        var session = new AndroidDebugSession(root, adb, device_id, package_id, symbols);
        boolean ready = false;
        try {
            session.Attach(lldb_server, indicator);
            ready = true;
            return session;
        } finally {
            if (!ready) {
                session.close();
            }
        }
    }

    private static JsonObject ReadJson(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Path FindCompilerRoot(Path project, Path symbols) throws IOException {
        for (Path directory = symbols.getParent(); directory != null; directory = directory.getParent()) {
            if (Files.isSameFile(directory, project)) {
                return directory;
            }
        }
        return project;
    }

    private static Path FindLldbServer(Path ndk, String abi) throws IOException, ExecutionException {
        String arch = abi.equals("arm64-v8a") ? "aarch64" : abi;
        Path prebuilt = ndk.resolve("toolchains/llvm/prebuilt");
        try (var paths = Files.find(prebuilt, 8,
                (path, attributes) -> attributes.isRegularFile() && path.endsWith(Path.of("linux", arch, "lldb-server")))) {
            return paths.findFirst().orElseThrow(() ->
                    new ExecutionException("The selected Android NDK has no LLDB server for " + abi));
        }
    }

    private void Attach(Path lldb_server, ProgressIndicator indicator) throws ExecutionException {
        indicator.setText("Preparing Android LLDB");
        var pid = AwaitPid(indicator);
        String executable = RunAdb(indicator, "shell", "run-as", package_id_, "readlink", "/proc/" + pid + "/exe");
        if (!executable.startsWith("/") || !executable.endsWith("/app_process64")) {
            throw new ExecutionException("Android did not start the expected 64-bit application process.");
        }
        RunAdb(indicator, "pull", executable, AppProcess().toString());
        RunAdb(indicator, "pull", "/system/bin/linker64", work_.resolve("linker64").toString());
        var stage = Adb("shell", "-T", "run-as", package_id_, "sh", "-c",
                "'mkdir -p code_cache && cat > " + server_path_ + " && chmod 700 " + server_path_ + "'");
        Run(stage.withInput(lldb_server.toFile()), indicator, 60_000);

        forward_requested_ = true;
        String port = RunAdb(indicator, "forward", "tcp:0", forward_target_);
        if (!port.matches("[0-9]{1,5}")) {
            throw new ExecutionException("ADB did not return a local LLDB port.");
        }
        port_ = port;
        server_ = new OSProcessHandler(Adb("shell", "-T", "-n", "run-as", package_id_,
                "./" + server_path_, "gdbserver", "unix-abstract://" + socket_, "--attach", pid));
        server_.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key output_type) {
                if (server_output_.length() < 4096) {
                    server_output_.append(event.getText());
                }
            }
        });
        server_.startNotify();

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && !server_.isProcessTerminated()) {
            String status = RunAdb(indicator, "shell", "run-as", package_id_, "cat", "/proc/" + pid + "/status");
            if (traced_process.matcher(status).find()) {
                return;
            }
            Pause(indicator);
        }
        throw new ExecutionException("The Android LLDB server could not attach.\n" + server_output_.toString().strip());
    }

    private String AwaitPid(ProgressIndicator indicator) throws ExecutionException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            var output = new CapturingProcessHandler(Adb("shell", "pidof", package_id_))
                    .runProcessWithProgressIndicator(indicator, command_timeout);
            String pid = output.getStdout().strip();
            if (output.getExitCode() == 0 && pid.matches("[1-9][0-9]*")) {
                return pid;
            }
            Pause(indicator);
        } while (System.nanoTime() < deadline);
        throw new ExecutionException("The Android application process did not start: " + package_id_);
    }

    private static void Pause(ProgressIndicator indicator) {
        com.intellij.util.TimeoutUtil.sleep(100);
        indicator.checkCanceled();
    }

    Path AppProcess() {
        return work_.resolve("app_process64");
    }

    List<String> ExecutableSearchPaths() {
        return List.of(work_.toString(), symbols_.getParent().toString());
    }

    Path CompilerRoot() {
        return compiler_root_;
    }

    Path ProjectRoot() {
        return root_;
    }

    String Endpoint() {
        return "connect://127.0.0.1:" + port_;
    }

    private GeneralCommandLine Adb(String... arguments) {
        var command = new ArrayList<>(List.of("-s", device_));
        command.addAll(List.of(arguments));
        return HuxerUICommand.LocalCommand(adb_.toString(), root_, command);
    }

    private String RunAdb(ProgressIndicator indicator, String... arguments) throws ExecutionException {
        return Run(Adb(arguments), indicator, command_timeout);
    }

    private static String Run(GeneralCommandLine command, ProgressIndicator indicator, int timeout)
            throws ExecutionException {
        var output = new CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, timeout);
        indicator.checkCanceled();
        if (output.isTimeout() || output.getExitCode() != 0) {
            String detail = output.isTimeout() ? "Timed out." : output.getStderr().strip();
            throw new ExecutionException(command.getCommandLineString() + "\n" + detail);
        }
        return output.getStdout().strip();
    }

    @Override
    public void close() {
        if (server_ != null && !server_.isProcessTerminated()) {
            server_.destroyProcess();
            server_.waitFor(2000);
        }
        if (forward_requested_) {
            RemoveForward();
        }
        Cleanup("shell", "run-as", package_id_, "rm", "-f", server_path_);
        FileUtil.delete(work_.toFile());
    }

    private void RemoveForward() {
        try {
            var output = new CapturingProcessHandler(Adb("forward", "--list")).runProcess(3000);
            if (output.isTimeout() || output.getExitCode() != 0) {
                return;
            }
            for (String line : output.getStdout().lines().toList()) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length == 3 && device_.equals(fields[0]) && forward_target_.equals(fields[2])
                        && fields[1].matches("tcp:[0-9]{1,5}")) {
                    Cleanup("forward", "--remove", fields[1]);
                }
            }
        } catch (ExecutionException ignored) {
            // ADB may be unavailable during cleanup.
        }
    }

    private void Cleanup(String... arguments) {
        try {
            new CapturingProcessHandler(Adb(arguments)).runProcess(3000);
        } catch (ExecutionException ignored) {
            // The device may have disconnected.
        }
    }
}
