package org.huxerui.clion.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.AsyncProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.cidr.execution.TrivialRunParameters;
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess;
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver;
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public final class HuxerUIAndroidDebugRunner extends AsyncProgramRunner<RunnerSettings> {
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
    protected @NotNull Promise<RunContentDescriptor> execute(
            @NotNull ExecutionEnvironment environment, @NotNull RunProfileState state) throws ExecutionException {
        var project = environment.getProject();
        var service = HuxerUIProjectService.Get(project);
        var configuration = (HuxerUIRunConfiguration) environment.getRunProfile();
        var root = service.ApplicationRoot();
        if (root == null) {
            throw new ExecutionException("The HuxerUI application directory is unavailable.");
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        if (!service.TryStartTask()) {
            throw new ExecutionException("Stop the active HuxerUI session first.");
        }

        var result = new AsyncPromise<RunContentDescriptor>();
        new Task.Backgroundable(project, "Preparing Android Debugging", true) {
            private final AtomicBoolean released_ = new AtomicBoolean();
            private AndroidDebugSession device_session_;
            private boolean started_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    device_session_ = AndroidDebugSession.Prepare(project, configuration, root, indicator);
                } catch (ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    result.setError(error);
                }
            }

            @Override
            public void onSuccess() {
                if (device_session_ == null || project.isDisposed()) {
                    result.cancel();
                    return;
                }
                try {
                    var debugger = XDebuggerManager.getInstance(project).startSession(environment,
                            new XDebugProcessStarter() {
                                @Override
                                public @NotNull XDebugProcess start(@NotNull XDebugSession session)
                                        throws ExecutionException {
                                    var process = CreateDebugProcess(session, device_session_);
                                    session.addSessionListener(new XDebugSessionListener() {
                                        @Override
                                        public void sessionStopped() {
                                            Release();
                                        }
                                    });
                                    process.start();
                                    return process;
                                }
                            });
                    started_ = true;
                    result.setResult(debugger.getRunContentDescriptor());
                } catch (Exception error) {
                    result.setError(error);
                }
            }

            @Override
            public void onCancel() {
                result.cancel();
            }

            @Override
            public void onFinished() {
                if (!started_) {
                    Release();
                }
            }

            private void Release() {
                if (released_.compareAndSet(false, true)) {
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            if (device_session_ != null) {
                                device_session_.close();
                            }
                        } finally {
                            service.FinishTask();
                        }
                    });
                }
            }
        }.queue();
        return result;
    }

    private static CidrDebugProcess CreateDebugProcess(XDebugSession session, AndroidDebugSession device)
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
