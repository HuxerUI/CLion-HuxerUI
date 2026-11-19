package org.huxerui.clion.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.runners.AsyncProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class HuxerUIMobileDebugRunner<S extends HuxerUIMobileDebugRunner.Session>
        extends AsyncProgramRunner<RunnerSettings> {
    interface Session extends AutoCloseable {
        @Override
        void close();
    }

    protected abstract S Prepare(Project project, HuxerUIRunConfiguration configuration, Path root,
            ProgressIndicator indicator) throws Exception;

    protected abstract CidrDebugProcess CreateDebugProcess(XDebugSession session, S device)
            throws ExecutionException;

    static Path FindCompilerRoot(Path project, Path artifact) throws IOException {
        for (Path directory = artifact.getParent(); directory != null; directory = directory.getParent()) {
            if (Files.isSameFile(directory, project)) {
                return directory;
            }
        }
        return project;
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
        new Task.Backgroundable(project, "Preparing HuxerUI Debugging", true) {
            private final AtomicBoolean released_ = new AtomicBoolean();
            private S device_session_;
            private boolean started_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    device_session_ = Prepare(project, configuration, root, indicator);
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
}
