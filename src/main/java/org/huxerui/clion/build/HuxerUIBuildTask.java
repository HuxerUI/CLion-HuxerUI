package org.huxerui.clion.build;

import com.intellij.build.BuildViewManager;
import com.intellij.build.DefaultBuildDescriptor;
import com.intellij.build.events.impl.FailureResultImpl;
import com.intellij.build.events.impl.SuccessResultImpl;
import com.intellij.build.progress.BuildProgress;
import com.intellij.build.progress.BuildProgressDescriptor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class HuxerUIBuildTask {
    private HuxerUIBuildTask() {}

    public static void Queue(Project project, String title, Path root, Supplier<GeneralCommandLine> command,
            Runnable on_success) {
        FileDocumentManager.getInstance().saveAllDocuments();
        new Task.Backgroundable(project, title, true) {
            private boolean succeeded_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                HuxerUIProjectService service = HuxerUIProjectService.Get(project);
                if (!service.TryStartTask()) {
                    HuxerUINotifications.error(project, title, "Stop the active HuxerUI session first.");
                    return;
                }
                try {
                    succeeded_ = Execute(project, title, root, command, indicator);
                } finally {
                    service.FinishTask();
                }
            }

            @Override
            public void onSuccess() {
                if (succeeded_ && on_success != null && !project.isDisposed()) {
                    on_success.run();
                }
            }
        }.queue();
    }

    public static boolean Execute(Project project, String title, Path root, Supplier<GeneralCommandLine> command,
            ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        indicator.setText(title);
        DefaultBuildDescriptor descriptor = new DefaultBuildDescriptor(new Object(), title, root.toString(),
                System.currentTimeMillis());
        descriptor.setActivateToolWindowWhenAdded(true);
        descriptor.setActivateToolWindowWhenFailed(true);
        BuildProgress<BuildProgressDescriptor> build = BuildViewManager.createBuildProgress(project);
        build.start(new BuildProgressDescriptor() {
            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public DefaultBuildDescriptor getBuildDescriptor() {
                return descriptor;
            }
        });
        try {
            indicator.checkCanceled();
            GeneralCommandLine line = command.get();
            build.output("> " + line.getCommandLineString() + "\n", ProcessOutputType.SYSTEM);
            var process = new KillableColoredProcessHandler(line);
            var output = new BuildOutput(build, root);
            process.addProcessListener(output);
            process.startNotify();
            while (!process.waitFor(100)) {
                if (indicator.isCanceled() || project.isDisposed()) {
                    process.destroyProcess();
                    if (!process.waitFor(1500)) {
                        process.killProcess();
                        process.waitFor(1500);
                    }
                    throw new ProcessCanceledException();
                }
            }
            output.Flush();
            indicator.checkCanceled();
            Integer exit_code = process.getExitCode();
            if (exit_code == null || exit_code != 0) {
                String message = title + " exited with code " + exit_code;
                build.finish(System.currentTimeMillis(), message, new FailureResultImpl(message));
                return false;
            }
            build.finish(System.currentTimeMillis(), "Finished", new SuccessResultImpl());
            return true;
        } catch (ProcessCanceledException error) {
            build.cancel();
            throw error;
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            build.output(message + "\n", ProcessOutputType.STDERR);
            build.finish(System.currentTimeMillis(), "Failed", new FailureResultImpl(message, error));
            return false;
        }
    }

    private static final class BuildOutput implements ProcessListener {
        private final BuildProgress<?> build_;
        private final Path root_;
        private final Map<ProcessOutputType, StringBuilder> lines_ = new HashMap<>();

        private BuildOutput(BuildProgress<?> build, Path root) {
            build_ = build;
            root_ = root;
        }

        @Override
        public synchronized void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key output_type) {
            ProcessOutputType type = ProcessOutputType.fromKey(output_type);
            build_.output(event.getText(), type == null ? ProcessOutputType.SYSTEM : type);
            if (type == null || type == ProcessOutputType.SYSTEM) {
                return;
            }
            StringBuilder pending = lines_.computeIfAbsent(type.getBaseOutputType(), key -> new StringBuilder());
            pending.append(event.getText());
            int end;
            while ((end = pending.indexOf("\n")) >= 0) {
                Report(pending.substring(0, end));
                pending.delete(0, end + 1);
            }
        }

        synchronized void Flush() {
            for (StringBuilder pending : lines_.values()) {
                Report(pending.toString());
            }
        }

        private void Report(String line) {
            var diagnostic = HuxerUICliDiagnostic.Parse(root_, line);
            if (diagnostic != null) {
                build_.fileMessage(diagnostic.message(), line, diagnostic.kind(), diagnostic.position());
            }
        }
    }
}
