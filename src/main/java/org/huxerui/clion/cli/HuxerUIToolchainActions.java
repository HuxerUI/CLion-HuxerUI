package org.huxerui.clion.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.PlatformNames;
import org.huxerui.clion.build.HuxerUIBuildTask;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.project.HuxerUIProjectStartupActivity;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HuxerUIToolchainActions extends ActionGroup implements DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        var service = project == null ? null : HuxerUIProjectService.Get(project);
        boolean visible = service != null && service.IsProject();
        event.getPresentation().setVisible(visible);
        event.getPresentation().setEnabled(visible && TrustedProjects.isProjectTrusted(project) && !service.HasActiveTask()
                && (service.IsMcppProject() || service.SdkCli() != null));
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent event) {
        Project project = event == null ? null : event.getProject();
        if (project == null) {
            return AnAction.EMPTY_ARRAY;
        }
        var service = HuxerUIProjectService.Get(project);
        var doctor = DumbAwareAction.create("Diagnose Environment...", ignored -> Diagnose(project, false));
        return service.IsMcppProject() ? new AnAction[]{doctor} : new AnAction[]{doctor,
                DumbAwareAction.create("Set Up Toolchain...", ignored -> Diagnose(project, true))};
    }

    private static void Diagnose(Project project, boolean setup) {
        var service = HuxerUIProjectService.Get(project);
        Path root = service.ApplicationRoot();
        if (service.IsMcppProject()) {
            Path directory = HuxerUICommand.ProjectRoot(project);
            HuxerUIBuildTask.Queue(project, "Diagnose mcpp Environment", directory, () -> {
                HuxerUICommand.RequireTrusted(project);
                return HuxerUICommand.LocalCommand("mcpp", directory, List.of("--version"));
            }, null);
            return;
        }
        if (root == null) {
            root = HuxerUICommand.ProjectRoot(project);
        }
        List<String> platforms = service.BuildablePlatforms();
        if (setup && platforms.isEmpty()) {
            HuxerUINotifications.error(project, "HuxerUI toolchain setup", "Enable a platform supported on this host.");
            return;
        }
        int selection = platforms.size() <= 1 ? 0 : Messages.showChooseDialog(project,
                "Select a target platform", setup ? "Set Up HuxerUI Toolchain" : "Diagnose HuxerUI Environment", null,
                platforms.stream().map(PlatformNames::DisplayName).toArray(String[]::new),
                PlatformNames.DisplayName(platforms.getFirst()));
        if (selection < 0) {
            return;
        }
        String platform = platforms.isEmpty() ? "" : platforms.get(selection);
        List<String> operations = List.of("Build", "Run", "Package");
        int index = platform.isEmpty() ? 0 : Messages.showChooseDialog(project, "Check requirements for",
                "HuxerUI Toolchain", null, operations.toArray(String[]::new), "Build");
        if (index < 0) {
            return;
        }
        List<String> flags = new ArrayList<>();
        if (!platform.isEmpty()) {
            flags.add(platform);
        }
        flags.addAll(List.of("--operation", operations.get(index).toLowerCase(java.util.Locale.ROOT)));
        if (setup) {
            ConfirmSetup(project, root, platform, flags);
        } else {
            Path directory = root;
            HuxerUIBuildTask.Queue(project, "Diagnose HuxerUI Environment", directory,
                    () -> HuxerUICommand.Create(project, directory, Arguments("doctor", flags)), null);
        }
    }

    private static void ConfirmSetup(Project project, Path directory, String platform, List<String> flags) {
        FileDocumentManager.getInstance().saveAllDocuments();
        var service = HuxerUIProjectService.Get(project);
        Path home = service.SdkHome();
        Path cli = home == null ? null : SdkLayout.FindCli(home);
        if (cli == null) {
            return;
        }
        new Task.Backgroundable(project, "Check HuxerUI Toolchain", true) {
            private JsonObject plan_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    HuxerUICommand.RequireTrusted(project);
                    var arguments = Arguments("doctor", flags);
                    arguments.add("--json");
                    var handler = new CapturingProcessHandler(HuxerUICommand.Create(home, cli, directory, arguments));
                    var output = handler.runProcessWithProgressIndicator(indicator, 60_000);
                    indicator.checkCanceled();
                    if (output.getExitCode() != 1 || output.getStdout().isBlank()) {
                        HuxerUICommand.RequireSuccess(output);
                    }
                    var plan = JsonParser.parseString(output.getStdout()).getAsJsonObject();
                    if (plan.get("schema").getAsInt() != 1 || plan.getAsJsonArray("targets").size() != 1
                            || !platform.equals(plan.getAsJsonArray("targets").get(0).getAsJsonObject()
                                    .get("platform").getAsString())) {
                        throw new IllegalStateException("The SDK CLI returned an unexpected toolchain plan");
                    }
                    plan_ = plan;
                } catch (ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    HuxerUINotifications.error(project, "HuxerUI toolchain check failed", String.valueOf(error.getMessage()));
                }
            }

            @Override
            public void onSuccess() {
                if (plan_ == null || project.isDisposed()) {
                    return;
                }
                var errors = plan_.getAsJsonArray("projectErrors");
                if (!errors.isEmpty()) {
                    var messages = new ArrayList<String>();
                    errors.forEach(error -> messages.add(error.getAsString()));
                    Messages.showErrorDialog(project, String.join("\n", messages), "HuxerUI Project Issues");
                    return;
                }
                var target = plan_.getAsJsonArray("targets").get(0).getAsJsonObject();
                if (target.get("ready").getAsBoolean()) {
                    HuxerUINotifications.info(project, "HuxerUI toolchain ready", PlatformNames.DisplayName(platform));
                    return;
                }
                List<String> descriptions = new ArrayList<>();
                boolean automatic = false;
                for (var item : plan_.getAsJsonArray("actions")) {
                    var action = item.getAsJsonObject();
                    descriptions.add(action.get("description").getAsString());
                    automatic |= action.get("kind").getAsString().equals("automatic");
                }
                String title = "Set Up " + PlatformNames.DisplayName(platform) + " Toolchain";
                String details = String.join("\n", descriptions);
                if (!automatic) {
                    Messages.showWarningDialog(project,
                            details.isBlank() ? "Run Diagnose Environment to see missing prerequisites." : details, title);
                } else if (Messages.showOkCancelDialog(project, details, title, "Install", "Cancel",
                        Messages.getQuestionIcon()) == Messages.OK) {
                    var arguments = Arguments("setup", flags);
                    arguments.add("--yes");
                    HuxerUIBuildTask.Queue(project, title, directory, () -> {
                        HuxerUICommand.RequireTrusted(project);
                        return HuxerUICommand.Create(home, cli, directory, arguments);
                    }, () -> HuxerUIProjectStartupActivity.ConfigureAfterSdkAvailable(project));
                }
            }
        }.queue();
    }

    private static List<String> Arguments(String command, List<String> flags) {
        var arguments = new ArrayList<>(List.of(command));
        arguments.addAll(flags);
        return arguments;
    }
}
