package org.huxerui.clion.build;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.huxerui.clion.PlatformNames;
import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HuxerUIBuildActionGroup extends ActionGroup implements DumbAware {
    public HuxerUIBuildActionGroup() {
        super("HuxerUI Build and Package", true);
        getTemplatePresentation().setDescription("Build or package a HuxerUI application");
        getTemplatePresentation().setIcon(AllIcons.Actions.Compile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent event) {
        Project project = event == null ? null : event.getProject();
        if (project == null) {
            return AnAction.EMPTY_ARRAY;
        }
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        if (service.IsMcppProject()) {
            Path directory = HuxerUICommand.ProjectRoot(project);
            return new AnAction[]{
                    DumbAwareAction.create("Build Debug", ignored -> BuildMcpp(project, directory, "debug")),
                    DumbAwareAction.create("Build Release", ignored -> BuildMcpp(project, directory, "release"))
            };
        }
        Path root = service.ApplicationRoot();
        if (root == null) {
            return AnAction.EMPTY_ARRAY;
        }
        List<String> platforms = service.BuildablePlatforms();
        List<AnAction> actions = new ArrayList<>();
        if (platforms.size() > 1) {
            String all_platforms = String.join(",", platforms);
            DefaultActionGroup all = new DefaultActionGroup("All Available Platforms", true);
            AddActions(all, project, root, all_platforms, false);
            actions.add(all);
        }
        for (String platform : platforms) {
            DefaultActionGroup target = new DefaultActionGroup(PlatformNames.DisplayName(platform), true);
            AddActions(target, project, root, platform, true);
            actions.add(target);
        }
        Path dist = root.resolve("dist");
        if (Files.isDirectory(dist) && RevealFileAction.isDirectoryOpenSupported()) {
            actions.add(DumbAwareAction.create("Open Artifacts", ignored -> RevealFileAction.openDirectory(dist)));
        }
        return actions.toArray(AnAction[]::new);
    }

    private static void AddActions(DefaultActionGroup group, Project project, Path root, String platforms,
            boolean advanced) {
        group.add(DumbAwareAction.create("Build Debug",
                ignored -> Run(project, root, platforms, "build", new HuxerUIBuildOptions("debug"))));
        group.add(DumbAwareAction.create("Build Release",
                ignored -> Run(project, root, platforms, "build", new HuxerUIBuildOptions("release"))));
        group.add(DumbAwareAction.create("Package Release",
                ignored -> Run(project, root, platforms, "package", new HuxerUIBuildOptions("release"))));
        if (advanced) {
            group.addSeparator();
            group.add(DumbAwareAction.create("Build with Options...",
                    ignored -> RunWithOptions(project, root, platforms, "build")));
            group.add(DumbAwareAction.create("Package with Options...",
                    ignored -> RunWithOptions(project, root, platforms, "package")));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        HuxerUIProjectService service = project == null ? null : HuxerUIProjectService.Get(project);
        boolean mcpp = service != null && service.IsMcppProject();
        boolean visible = mcpp || service != null && service.ApplicationRoot() != null;
        event.getPresentation().setVisible(visible);
        event.getPresentation().setEnabled(visible && TrustedProjects.isProjectTrusted(project)
                && (mcpp || service.SdkCli() != null) && !service.HasActiveTask());
    }

    private static void BuildMcpp(Project project, Path root, String configuration) {
        HuxerUIBuildTask.Queue(project, "Build HuxerUI mcpp (" + configuration + ")", root, () -> {
            HuxerUICommand.RequireTrusted(project);
            return HuxerUICommand.Mcpp(root, "build", configuration);
        }, null);
    }

    private static void RunWithOptions(Project project, Path root, String platform, String operation) {
        OptionsDialog dialog = new OptionsDialog(project, platform, operation);
        if (dialog.showAndGet()) {
            Run(project, root, platform, operation, dialog.Options());
        }
    }

    private static void Run(Project project, Path root, String platforms, String operation,
            HuxerUIBuildOptions options) {
        String title = (operation.equals("package") ? "Package HuxerUI " : "Build HuxerUI ")
                + platforms + " (" + options.configuration() + ")";
        HuxerUIBuildTask.Queue(project, title, root,
                () -> HuxerUICommand.Create(project, root, options.Arguments(operation, platforms)), null);
    }

    private static final class OptionsDialog extends DialogWrapper {
        private final ComboBox<String> configuration_ = new ComboBox<>(new String[]{"debug", "release"});
        private final JBTextField abis_ = new JBTextField(24);
        private final ComboBox<String> optimization_ = new ComboBox<>(new String[]{"", "default", "size", "minsize"});
        private final ComboBox<String> lto_ = new ComboBox<>(new String[]{"", "on", "off"});
        private final String platform_;
        private final String operation_;

        private OptionsDialog(Project project, String platform, String operation) {
            super(project);
            platform_ = platform;
            operation_ = operation;
            boolean packaging = operation.equals("package");
            setTitle((packaging ? "Package HuxerUI for " : "Build HuxerUI for ") + PlatformNames.DisplayName(platform));
            setOKButtonText(packaging ? "Package" : "Build");
            configuration_.setSelectedItem(packaging ? "release" : "debug");
            abis_.getEmptyText().setText("SDK default");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("Configuration", configuration_)
                    .addLabeledComponent("ABIs", abis_)
                    .addTooltip("Comma-separated. Available ABIs: " + String.join(", ", HuxerUIBuildOptions.Abis(platform_)))
                    .addLabeledComponent("Optimization", optimization_)
                    .addLabeledComponent("LTO", lto_)
                    .getPanel();
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            try {
                Options().Arguments(operation_, platform_);
                return null;
            } catch (IllegalArgumentException error) {
                return new ValidationInfo(error.getMessage(), abis_);
            }
        }

        private HuxerUIBuildOptions Options() {
            return new HuxerUIBuildOptions(configuration_.getItem(), abis_.getText().strip(), optimization_.getItem(),
                    lto_.getItem());
        }
    }
}
