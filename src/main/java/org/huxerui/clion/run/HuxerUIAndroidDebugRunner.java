package org.huxerui.clion.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.jetbrains.cidr.execution.TrivialRunParameters;
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess;
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class HuxerUIAndroidDebugRunner extends HuxerUIMobileDebugRunner<AndroidDebugSession> {
    @Override
    public @NotNull String getRunnerId() {
        return "HuxerUI.AndroidDebug";
    }

    @Override
    public boolean canRun(@NotNull String executor_id, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executor_id)
                && profile instanceof HuxerUIRunConfiguration configuration
                && configuration.GetPlatform().equals("android") && !configuration.IsWatch()
                && !configuration.GetDeviceId().isBlank() && LLDBDriverConfiguration.hasBundledLLDB();
    }

    @Override
    protected AndroidDebugSession Prepare(Project project, HuxerUIRunConfiguration configuration, Path root,
            ProgressIndicator indicator) throws Exception {
        return AndroidDebugSession.Prepare(project, configuration, root, indicator);
    }

    @Override
    protected CidrDebugProcess CreateDebugProcess(XDebugSession session, AndroidDebugSession device)
            throws ExecutionException {
        var parameters = new TrivialRunParameters(new LLDBDriverConfiguration(),
                new GeneralCommandLine(device.AppProcess().toString()));
        var console = TextConsoleBuilderFactory.getInstance().createBuilder(session.getProject());
        return new CidrDebugProcess(parameters, session, console) {
            @Override
            protected @NotNull DebuggerDriver.Inferior doLoadTarget(@NotNull DebuggerDriver driver)
                    throws ExecutionException {
                var lldb = (LLDBDriver) driver;
                lldb.executeInterpreterCommand("platform select remote-android");
                var mappings = device.CompilerRoot().equals(device.ProjectRoot())
                        ? List.<DebuggerDriver.PathMapping>of()
                        : List.of(new DebuggerDriver.PathMapping(
                                device.CompilerRoot().toString(), device.ProjectRoot().toString()));
                var inferior = driver.loadForRemote(device.Endpoint(), device.AppProcess().toFile(), null, mappings);
                String directories = device.ExecutableSearchPaths().stream()
                        .map(path -> "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        .collect(Collectors.joining(" "));
                lldb.executeInterpreterCommand("settings set target.exec-search-paths " + directories);
                // lldb-server can report its attach SIGSTOP after the process has resumed.
                lldb.executeInterpreterCommand("process handle -s false -n false -p false SIGSTOP");
                return inferior;
            }

            @Override
            public boolean isDetachDefault() {
                return true;
            }
        };
    }
}
