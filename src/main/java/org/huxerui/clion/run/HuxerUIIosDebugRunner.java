package org.huxerui.clion.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.xdebugger.XDebugSession;
import com.jetbrains.cidr.execution.TrivialRunParameters;
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess;
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class HuxerUIIosDebugRunner extends HuxerUIMobileDebugRunner<IosDebugSession> {
    @Override
    public @NotNull String getRunnerId() {
        return "HuxerUI.IosDebug";
    }

    @Override
    public boolean canRun(@NotNull String executor_id, @NotNull RunProfile profile) {
        return SystemInfo.isMac && DefaultDebugExecutor.EXECUTOR_ID.equals(executor_id)
                && profile instanceof HuxerUIRunConfiguration configuration
                && configuration.GetPlatform().equals("ios") && !configuration.IsWatch()
                && !configuration.GetDeviceId().isBlank() && LLDBDriverConfiguration.hasBundledLLDB();
    }

    @Override
    protected IosDebugSession Prepare(Project project, HuxerUIRunConfiguration configuration, Path root,
            ProgressIndicator indicator) throws Exception {
        return IosDebugSession.Prepare(project, configuration, root, indicator);
    }

    @Override
    protected CidrDebugProcess CreateDebugProcess(XDebugSession session, IosDebugSession target)
            throws ExecutionException {
        var parameters = new TrivialRunParameters(new LLDBDriverConfiguration(),
                new GeneralCommandLine(target.Executable().toString()));
        var console = TextConsoleBuilderFactory.getInstance().createBuilder(session.getProject());
        return new CidrDebugProcess(parameters, session, console) {
            @Override
            protected @NotNull DebuggerDriver.Inferior doLoadTarget(@NotNull DebuggerDriver driver)
                    throws ExecutionException {
                var inferior = driver.loadForAttach(target.Pid());
                var lldb = (LLDBDriver) driver;
                if (!target.CompilerRoot().equals(target.ProjectRoot())) {
                    lldb.executeInterpreterCommand("settings set target.source-map "
                            + QuotePath(target.CompilerRoot()) + " " + QuotePath(target.ProjectRoot()));
                }
                lldb.executeInterpreterCommand("target modules add " + QuotePath(target.Executable()));
                return inferior;
            }
        };
    }

    private static String QuotePath(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
