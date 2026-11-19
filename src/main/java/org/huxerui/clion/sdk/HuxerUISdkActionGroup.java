package org.huxerui.clion.sdk;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.project.HuxerUIProjectStartupActivity;
import org.huxerui.clion.settings.HuxerUISettings;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HuxerUISdkActionGroup extends ActionGroup implements DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        boolean visible = project != null && HuxerUIProjectService.Get(project).IsCMakeProject();
        event.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent event) {
        Project project = event == null ? null : event.getProject();
        if (project == null || !HuxerUIProjectService.Get(project).IsCMakeProject()) {
            return AnAction.EMPTY_ARRAY;
        }
        var service = HuxerUIProjectService.Get(project);
        Path current = service.SdkHome();
        List<AnAction> actions = new ArrayList<>();
        actions.add(DumbAwareAction.create("Select Project SDK...", ignored -> SelectSdk(project)));
        actions.add(DumbAwareAction.create("Install SDK Version...", ignored -> {
            String version = Messages.showInputDialog(project, "Exact release version, for example 0.5.0",
                    "Install HuxerUI SDK Version", Messages.getQuestionIcon());
            if (version != null && !version.isBlank()) {
                InstallSdkAction.Install(project, version.strip(), null);
            }
        }));
        if (TrustedProjects.isProjectTrusted(project)) {
            actions.add(DumbAwareAction.create("Build CLI and Use Source Checkout...",
                    ignored -> Browse(project, true)));
            if (current != null && SdkLayout.IsSource(current)) {
                actions.add(DumbAwareAction.create("Rebuild Source CLI",
                        ignored -> SourceCliBuild.Queue(project, current)));
            }
        }
        return actions.toArray(AnAction[]::new);
    }

    public static void SelectSdk(Project project) {
        var service = HuxerUIProjectService.Get(project);
        var installed = HuxerUISettings.InstalledSdks();
        List<String> choices = new ArrayList<>(List.of("Use IDE Default", "Browse for SDK or Source Checkout..."));
        installed.stream().map(Path::toString).forEach(choices::add);
        String initial = service.SdkOverride();
        if (!initial.isBlank() && !choices.contains(initial)) {
            choices.add(initial);
        }
        int selection = Messages.showChooseDialog(project, "Select the SDK for this CLion project",
                "Select HuxerUI SDK", null, choices.toArray(String[]::new),
                initial.isBlank() ? choices.getFirst() : initial);
        if (selection == 1) {
            Browse(project, false);
        } else if (selection >= 0) {
            Apply(project, selection == 0 ? "" : choices.get(selection));
        }
    }

    private static void Browse(Project project, boolean build_source) {
        var descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(build_source ? "Select HuxerUI Source Checkout" : "Select HuxerUI SDK");
        FileChooser.chooseFile(descriptor, project, null, file -> {
            Path home = file.toNioPath();
            boolean valid = build_source ? SdkLayout.IsSource(home) : SdkLayout.IsValid(home);
            if (!valid) {
                HuxerUINotifications.error(project, "Invalid HuxerUI SDK", home.toString());
            } else if (build_source) {
                SourceCliBuild.Queue(project, home);
            } else {
                Apply(project, home.toString());
                if (SdkLayout.IsSource(home) && SdkLayout.FindCli(home) == null && TrustedProjects.isProjectTrusted(project)
                        && Messages.showOkCancelDialog(project, "Build the standalone CLI for this checkout?",
                                "Build HuxerUI Source CLI", "Build CLI", "Later", Messages.getQuestionIcon())
                                == Messages.OK) {
                    SourceCliBuild.Queue(project, home);
                }
            }
        });
    }

    static void Apply(Project project, String home) {
        HuxerUIProjectService.Get(project).SetSdkOverride(home);
        HuxerUIProjectStartupActivity.ConfigureAfterSdkAvailable(project);
    }
}
