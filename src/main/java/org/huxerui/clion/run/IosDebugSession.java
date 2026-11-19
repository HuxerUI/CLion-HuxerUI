package org.huxerui.clion.run;

import com.google.gson.JsonParser;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.huxerui.clion.build.HuxerUIBuildTask;
import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class IosDebugSession implements HuxerUIMobileDebugRunner.Session {
    private static final int command_timeout = 15_000;
    private final Path root_;
    private final String device_;
    private final Path bundle_;
    private final Path executable_;
    private final Path compiler_root_;
    private final String bundle_id_;
    private ProcessHandle process_;

    private IosDebugSession(Path root, String device, Path plan) throws IOException, ExecutionException {
        root_ = root;
        device_ = device;
        var product = JsonParser.parseString(Files.readString(plan)).getAsJsonObject();
        bundle_ = Path.of(product.get("bundle").getAsString());
        executable_ = Path.of(product.get("artifact").getAsString());
        bundle_id_ = product.get("bundleIdentifier").getAsString();
        if (product.get("schema").getAsInt() != 1 || !bundle_.isAbsolute() || !Files.isDirectory(bundle_)
                || !executable_.startsWith(bundle_) || !Files.isRegularFile(executable_)
                || !bundle_id_.matches("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*")) {
            throw new ExecutionException("The iOS Debug application bundle is invalid: " + bundle_);
        }
        compiler_root_ = HuxerUIMobileDebugRunner.FindCompilerRoot(root, executable_);
    }

    static IosDebugSession Prepare(Project project, HuxerUIRunConfiguration configuration, Path root,
            ProgressIndicator indicator) throws Exception {
        HuxerUICommand.RequireTrusted(project);
        String device = configuration.GetDeviceId();
        RequireBootedSimulator(root, device, indicator);
        Path home = HuxerUIProjectService.Get(project).SdkHome();
        Path cli = home == null ? null : SdkLayout.FindCli(home);
        if (cli == null) {
            throw new ExecutionException("Select a built HuxerUI SDK from Tools | HuxerUI.");
        }
        var arguments = new HuxerUIBuildOptions("debug", "", "", "off").Arguments("build", "ios");
        arguments.addAll(List.of("--device", device));
        if (!HuxerUIBuildTask.Execute(project, "Build iOS Debug App", root,
                () -> HuxerUICommand.Create(home, cli, root, arguments), indicator)) {
            throw new ExecutionException("The iOS build failed. See the Build window.");
        }
        Path plan = root.resolve(".huxerui/build").resolve(BuildHomeKey(home.toRealPath()))
                .resolve("ios-simulator/debug-no-lto/huxerui-integration/app.json");
        var session = new IosDebugSession(root, device, plan);
        boolean ready = false;
        try {
            session.InstallAndLaunch(indicator);
            ready = true;
            return session;
        } finally {
            if (!ready) {
                session.close();
            }
        }
    }

    private static void RequireBootedSimulator(Path root, String device, ProgressIndicator indicator)
            throws ExecutionException {
        String output = Run(Xcrun(root, "simctl", "list", "devices", "booted", "--json"), indicator, command_timeout);
        var runtimes = JsonParser.parseString(output).getAsJsonObject().getAsJsonObject("devices");
        for (var runtime : runtimes.entrySet()) {
            for (var item : runtime.getValue().getAsJsonArray()) {
                if (device.equalsIgnoreCase(item.getAsJsonObject().get("udid").getAsString())) {
                    return;
                }
            }
        }
        throw new ExecutionException("Select a booted iOS simulator in Run Target to debug.");
    }

    private void InstallAndLaunch(ProgressIndicator indicator) throws ExecutionException {
        indicator.setText("Installing iOS Debug App");
        Run(Xcrun(root_, "simctl", "install", device_, bundle_.toString()), indicator, 120_000);

        indicator.setText("Launching iOS Debug App");
        indicator.checkCanceled();
        var launch = Xcrun(root_, "simctl", "launch", "--terminate-running-process", "--wait-for-debugger",
                device_, bundle_id_);
        // Capture the process before propagating cancellation or a launch error.
        var output = new CapturingProcessHandler(launch).runProcess(command_timeout);
        var result = Pattern.compile("(?m)^" + Pattern.quote(bundle_id_) + ": ([1-9]\\d*)$")
                .matcher(output.getStdout().strip());
        if (result.find()) {
            process_ = ProcessHandle.of(Integer.parseInt(result.group(1)))
                    .filter(process -> process.info().startInstant().isPresent()).orElse(null);
        }
        CheckOutput(launch, output);
        indicator.checkCanceled();
        if (process_ == null) {
            throw new ExecutionException("Could not identify the simulator process launched for " + bundle_id_);
        }
    }

    private static String BuildHomeKey(Path home) {
        // The HuxerUI CLI uses FNV-1a over the canonical SDK path for its build directory.
        long hash = 0xcbf29ce484222325L;
        for (byte value : home.toString().getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        }
        return "home-%016x".formatted(hash);
    }

    Path Executable() {
        return executable_;
    }

    int Pid() {
        return Math.toIntExact(process_.pid());
    }

    Path CompilerRoot() {
        return compiler_root_;
    }

    Path ProjectRoot() {
        return root_;
    }

    private static GeneralCommandLine Xcrun(Path root, String... arguments) {
        return HuxerUICommand.LocalCommand("/usr/bin/xcrun", root, List.of(arguments));
    }

    private static String Run(GeneralCommandLine command, ProgressIndicator indicator, int timeout)
            throws ExecutionException {
        var output = new CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, timeout);
        indicator.checkCanceled();
        return CheckOutput(command, output);
    }

    private static String CheckOutput(GeneralCommandLine command, ProcessOutput output) throws ExecutionException {
        if (output.isTimeout() || output.getExitCode() != 0) {
            String detail = output.isTimeout() ? "Timed out."
                    : (output.getStderr().isBlank() ? output.getStdout() : output.getStderr()).strip();
            throw new ExecutionException(command.getCommandLineString() + "\n" + detail);
        }
        return output.getStdout().strip();
    }

    @Override
    public void close() {
        if (process_ != null) {
            process_.destroyForcibly();
        }
    }
}
