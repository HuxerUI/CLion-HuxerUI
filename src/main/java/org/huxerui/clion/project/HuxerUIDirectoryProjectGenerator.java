package org.huxerui.clion.project;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.facet.ui.ValidationResult;
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep;
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator;
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase;
import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel;
import com.intellij.platform.DirectoryProjectGenerator;
import com.intellij.platform.ProjectGeneratorPeer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.cidr.cpp.cmake.CMakeSettings;
import com.jetbrains.cidr.cpp.cmake.projectWizard.generators.CLionProjectGenerator;
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineStart;
import org.huxerui.clion.PlatformNames;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.sdk.HuxerUISdk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class HuxerUIDirectoryProjectGenerator
        extends CLionProjectGenerator<HuxerUIDirectoryProjectGenerator.Settings>
        implements CustomStepProjectGenerator<HuxerUIDirectoryProjectGenerator.Settings> {
    private static final Logger log = Logger.getInstance(HuxerUIDirectoryProjectGenerator.class);
    private static final Icon icon = IconLoader.getIcon("/icons/huxerui.svg", HuxerUIDirectoryProjectGenerator.class);
    private final boolean application_;
    private final Peer peer_;

    protected HuxerUIDirectoryProjectGenerator(boolean application) {
        application_ = application;
        peer_ = new Peer(application);
    }

    record Settings(List<String> platforms, String project_id, String build_system, String template,
                    @Nullable HuxerUISdk sdk) {
        Settings {
            platforms = List.copyOf(platforms);
            project_id = project_id == null ? "" : project_id.trim();
        }
    }

    record CMakeProfilePlan(String name, boolean enabled, String generation_options) {}

    @Override
    public final @NotNull String getGroupName() {
        return "HuxerUI";
    }

    @Override
    public final @NotNull String getGroupDisplayName() {
        return "HuxerUI";
    }

    @Override
    public final int getGroupOrder() {
        return 800;
    }

    @Override
    public final @NotNull String getName() {
        return application_ ? "HuxerUI App" : "HuxerUI Library";
    }

    @Override
    public final @NotNull String getDescription() {
        return application_
                ? "Create a cross-platform HuxerUI application"
                : "Create a HuxerUI library with a CMake preview application or mcpp";
    }

    @Override
    public final @NotNull Icon getLogo() {
        return icon;
    }

    @Override
    public final @NotNull ProjectGeneratorPeer<Settings> createPeer() {
        return peer_;
    }

    @Override
    public final @NotNull JComponent getSettingsPanel() {
        return peer_.component_;
    }

    @Override
    public final @NotNull AbstractActionWithPanel createStep(@NotNull DirectoryProjectGenerator<Settings> generator,
            @NotNull AbstractNewProjectStep.AbstractCallback<Settings> callback) {
        Peer peer = new Peer(application_);
        return new ProjectSettingsStepBase<>(generator, callback) {
            @Override
            protected NotNullLazyValue<ProjectGeneratorPeer<Settings>> createLazyPeer() {
                return NotNullLazyValue.createConstantValue(peer);
            }

            @Override
            protected JPanel createAdvancedSettings() {
                JPanel panel = new JPanel(new BorderLayout());
                panel.setBorder(JBUI.Borders.emptyTop(8));
                panel.add(peer.component_, BorderLayout.NORTH);
                return panel;
            }

            @Override
            protected void initGeneratorListeners() {
                super.initGeneratorListeners();
                peer.check_valid_ = this::checkValid;
            }

            @Override
            public boolean checkValid() {
                if (myLocationField != null) {
                    peer.sdk_.Discover(getProjectLocation());
                }
                return super.checkValid();
            }

            @Override
            public void dispose() {
                peer.sdk_.Dispose();
                super.dispose();
            }
        };
    }

    @Override
    public final @NotNull ValidationResult validate(@NotNull String base_dir_path) {
        if (base_dir_path.isBlank()) {
            return new ValidationResult("Enter a HuxerUI project location.");
        }
        try {
            Path path = Path.of(base_dir_path);
            Path file_name = path.getFileName();
            if (file_name == null || !IsValidProjectName(file_name.toString(), application_)) {
                return new ValidationResult(application_
                        ? "App name must start with a letter and contain letters, digits, '_' or '-'."
                        : "Library name must contain non-empty letter or digit segments separated by '_' or '-'.");
            }
        } catch (InvalidPathException error) {
            return new ValidationResult("Enter a valid HuxerUI project location.");
        }
        return ValidationResult.OK;
    }

    @Override
    public final void generateProject(@NotNull Project project, @NotNull VirtualFile base_dir,
            @NotNull Settings settings, @NotNull Module module) {
        super.generateProject(project, base_dir, settings, module);
        Path destination = Path.of(base_dir.getPath());
        Runnable generation = () -> Generate(project, destination, settings);

        try {
            ProgressIndicator current = ProgressManager.getInstance().getProgressIndicator();
            if (current != null) {
                generation.run();
                return;
            }
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    generation, "Create " + getName(), true, project);
        } catch (ProcessCanceledException ignored) {
            // The project wizard owns cancellation and cleanup of the newly created project.
        } catch (RuntimeException error) {
            log.warn(getName() + " creation failed", error);
            com.intellij.openapi.ui.Messages.showErrorDialog(project, RootMessage(error), "Create " + getName());
        }
    }

    private void Generate(Project project, Path destination, Settings settings) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator == null) {
            throw new IllegalStateException("HuxerUI project generation has no progress indicator");
        }
        try {
            HuxerUISdk sdk = settings.sdk();
            if (sdk == null || !Files.isExecutable(sdk.cli())) {
                throw new IllegalStateException("Select an SDK with an executable HuxerUI 0.5 CLI.");
            }
            HuxerUICommand.RequireTrusted(project);
            HuxerUIProjectGenerator.CreateWithCli(destination, sdk.home(), sdk.cli(), application_, settings, indicator);
            HuxerUIProjectService.Get(project).SetSdkOverride(sdk.home().toString());
            VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(destination);
            if (root != null) {
                root.refresh(false, true);
            }
            if (settings.build_system().equals("cmake")) {
                ConfigureRecognizedProject(project, true);
            }
            HuxerUIProjectStartupActivity.Activate(project);
        } catch (ProcessCanceledException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(getName() + " creation failed: " + RootMessage(error), error);
        }
    }

    private static void ConfigureCMake(Project project, Path destination, List<String> platforms, boolean new_project) {
        HuxerUICommand.RequireTrusted(project);
        Path sdk = HuxerUIProjectService.Get(project).SdkHome();
        if (sdk == null) {
            return;
        }
        String sdk_home = sdk.toString();
        CMakeSettings settings = CMakeSettings.getInstance(project);
        Path emscripten_toolchain = null;
        if (platforms.contains("web")) {
            try {
                emscripten_toolchain = FindEmscriptenToolchain();
            } catch (ProcessCanceledException error) {
                throw error;
            } catch (Exception error) {
                org.huxerui.clion.HuxerUINotifications.error(project, "HuxerUI Web toolchain unavailable",
                        RootMessage(error));
                platforms = platforms.stream().filter(platform -> !platform.equals("web")).toList();
            }
        }
        settings.setProfiles(CreateCMakeProfiles(
                settings.getProfiles(), platforms, sdk_home, HuxerUIProjectService.HostPlatformId(),
                emscripten_toolchain, new_project));
        LinkCMakeProject(project, destination);
    }

    static void ConfigureRecognizedProject(Project project, boolean new_project) {
        String base_path = project.getBasePath();
        if (base_path == null) {
            throw new IllegalStateException("The project has no local base directory");
        }
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        Path root = service.ApplicationRoot();
        ConfigureCMake(project, root == null ? Path.of(base_path) : root, service.EnabledPlatforms(), new_project);
    }

    private static List<CMakeSettings.Profile> CreateCMakeProfiles(List<CMakeSettings.Profile> existing,
            List<String> platforms, String sdk_home, String host_platform, Path emscripten_toolchain,
            boolean new_project) {
        Map<String, CMakeSettings.Profile> profiles = new LinkedHashMap<>();
        if (!new_project) {
            for (CMakeSettings.Profile profile : existing) {
                profiles.put(profile.getName(), profile);
            }
        }
        for (CMakeProfilePlan plan : PlanCMakeProfiles(platforms, host_platform, emscripten_toolchain)) {
            CMakeSettings.Profile profile = profiles.getOrDefault(plan.name(), new CMakeSettings.Profile()
                    .withName(plan.name()).withBuildType("Debug").withEnabled(plan.enabled()));
            Map<String, String> environment = new HashMap<>(profile.getAdditionalEnvironment());
            environment.remove("HUXERUI_SDK_ROOT");
            environment.put("HUXERUI_HOME", sdk_home);
            profiles.put(plan.name(), profile.withEnvironment(profile.getPassSystemEnvironment(), environment)
                    .withGenerationOptions(SdkGenerationOptions(
                            profile.getGenerationOptions(), sdk_home, plan.generation_options())));
        }
        return List.copyOf(profiles.values());
    }

    static String SdkGenerationOptions(String existing, String sdk_home, String toolchain) {
        List<String> options = new ArrayList<>(ParametersListUtil.parse(existing == null ? "" : existing));
        options.removeIf(option -> CMakeDefinition(option, "HUXERUI_HOME")
                || (!toolchain.isBlank() && CMakeDefinition(option, "CMAKE_TOOLCHAIN_FILE")));
        options.add("-DHUXERUI_HOME=" + sdk_home);
        options.addAll(ParametersListUtil.parse(toolchain));
        return ParametersListUtil.join(options);
    }

    private static boolean CMakeDefinition(String argument, String name) {
        return argument.startsWith("-D" + name + "=") || argument.startsWith("-D" + name + ":");
    }

    static List<CMakeProfilePlan> PlanCMakeProfiles(
            List<String> platforms, String host_platform, Path emscripten_toolchain) {
        List<CMakeProfilePlan> configured = new ArrayList<>(2);
        if (platforms.contains(host_platform)) {
            configured.add(new CMakeProfilePlan(
                    ProfileName(PlatformNames.DisplayName(host_platform)), true, ""));
        }
        if (platforms.contains("web")) {
            if (emscripten_toolchain == null || !Files.isRegularFile(emscripten_toolchain)) {
                throw new IllegalStateException(
                        "HuxerUI Web requires an Emscripten toolchain; ensure em-config is available on PATH");
            }
            String toolchain_option = "-DCMAKE_TOOLCHAIN_FILE="
                    + QuoteGenerationOptionValue(emscripten_toolchain.toString());
            configured.add(new CMakeProfilePlan(ProfileName("Web"), true, toolchain_option));
        }
        if (configured.isEmpty()) {
            configured.add(new CMakeProfilePlan("HuxerUI Native Builds", false, ""));
        }
        return List.copyOf(configured);
    }

    private static String ProfileName(String platform) {
        return "HuxerUI " + platform + " Debug";
    }

    private static String QuoteGenerationOptionValue(String value) {
        if (value.chars().noneMatch(Character::isWhitespace)) {
            return value;
        }
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    private static Path FindEmscriptenToolchain() throws Exception {
        GeneralCommandLine command = new GeneralCommandLine("em-config", "EMSCRIPTEN_ROOT");
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        ProcessOutput output = new CapturingProcessHandler(command).runProcess(10_000);
        if (output.isTimeout()) {
            throw new IllegalStateException(
                    "HuxerUI Web timed out while locating the Emscripten toolchain with em-config");
        }
        if (output.getExitCode() != 0) {
            String message = (output.getStderr() + output.getStdout()).strip();
            throw new IllegalStateException(message.isEmpty()
                    ? "HuxerUI Web cannot locate the Emscripten toolchain with em-config"
                    : "HuxerUI Web cannot locate the Emscripten toolchain: " + message);
        }
        String root = output.getStdout().strip();
        if (root.isEmpty()) {
            throw new IllegalStateException("HuxerUI Web em-config returned an empty Emscripten root");
        }
        Path toolchain = Path.of(root, "cmake", "Modules", "Platform", "Emscripten.cmake");
        if (!Files.isRegularFile(toolchain)) {
            throw new IllegalStateException("HuxerUI Web Emscripten CMake toolchain does not exist: " + toolchain);
        }
        return toolchain;
    }

    static boolean IsValidApplicationId(String project_id) {
        return project_id.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+");
    }

    private static boolean IsValidProjectName(String project_name, boolean application) {
        return project_name.matches(application
                ? "[A-Za-z][A-Za-z0-9_-]*"
                : "[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)*");
    }

    private static void LinkCMakeProject(Project project, Path destination) {
        CMakeWorkspace workspace = CMakeWorkspace.getInstance(project);
        BuildersKt.launch(workspace.coroutineScope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                (scope, continuation) -> workspace.linkCMakeProject(destination.toFile(), continuation));
    }

    private static String RootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    static final class Peer implements ProjectGeneratorPeer<Settings> {
        private final boolean application_;
        private final JBTextField application_id_ = new JBTextField();
        private final ComboBox<String> build_system_ = new ComboBox<>(new String[]{"CMake", "mcpp"});
        private final ComboBox<String> template_ = new ComboBox<>(new String[]{"App", "Navigation"});
        private final JLabel platform_label_ = new JLabel();
        private final JLabel template_label_ = new JLabel("Template:");
        private final JLabel id_label_ = new JLabel("Project ID:");
        private final Map<String, JBCheckBox> platforms_ = new LinkedHashMap<>();
        private final JPanel platforms_panel_ = new JPanel(new GridLayout(0, 3, JBUI.scale(8), 0));
        private final JPanel platform_options_ = new JPanel(new BorderLayout());
        private final JPanel component_ = new JPanel(new GridBagLayout());
        final HuxerUIProjectSdkSelector sdk_ = new HuxerUIProjectSdkSelector(this::SettingsChanged);
        private final List<SettingsListener> listeners_ = new ArrayList<>();
        private Runnable check_valid_ = () -> {};

        Peer(boolean application) {
            application_ = application;
            platform_label_.setText(application ? "Target platforms:" : "Native code:");
            application_id_.setName("huxerui.application_id");
            application_id_.getEmptyText().setText("Optional, for example com.example.myapp");
            application_id_.setToolTipText("Reverse-domain Android package and Apple bundle identifier");
            application_id_.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent event) {
                    SettingsChanged();
                }
            });
            platforms_panel_.setOpaque(false);
            for (String platform : HuxerUIProjectService.all_platforms) {
                JBCheckBox check_box = new JBCheckBox(PlatformNames.DisplayName(platform),
                        application && !platform.equals("web"));
                check_box.setName("huxerui.platform." + platform);
                check_box.addActionListener(event -> SettingsChanged());
                platforms_.put(platform, check_box);
                platforms_panel_.add(check_box);
            }
            platform_options_.setOpaque(false);
            platform_options_.add(platforms_panel_, BorderLayout.NORTH);
            if (!application) {
                var help = new JBLabel("Select the platforms where this library needs native code.");
                help.setComponentStyle(UIUtil.ComponentStyle.SMALL);
                help.setFontColor(UIUtil.FontColor.BRIGHTER);
                platform_options_.add(help, BorderLayout.SOUTH);
            }
            BuildComponent();
            build_system_.addActionListener(event -> SettingsChanged());
            SettingsChanged();
        }

        @Override
        public @NotNull JComponent getComponent(
                @NotNull TextFieldWithBrowseButton location_field, @NotNull Runnable check_valid) {
            check_valid_ = check_valid;
            return component_;
        }

        @Override
        public void buildUI(@NotNull SettingsStep settings_step) {
            settings_step.addSettingsComponent(component_);
        }

        @Override
        public @NotNull Settings getSettings() {
            boolean cmake = IsCMake();
            return new Settings(cmake ? SelectedPlatforms() : List.of(), cmake ? application_id_.getText() : "",
                    cmake ? "cmake" : "mcpp", template_.getSelectedIndex() == 1 ? "navigation" : "app",
                    sdk_.Selection());
        }

        @Override
        public ValidationInfo validate() {
            String project_id = application_id_.getText().trim();
            if (!IsCMake()) {
                return sdk_.Validate();
            }
            if (!project_id.isEmpty() && !IsValidApplicationId(project_id)) {
                return new ValidationInfo(
                        "Application ID must be a lowercase reverse-domain identifier, for example com.example.myapp.",
                        application_id_);
            }
            if (application_ && SelectedPlatforms().isEmpty()) {
                return new ValidationInfo("An application requires at least one platform.", platforms_panel_);
            }
            return sdk_.Validate();
        }

        @Override
        public boolean isBackgroundJobRunning() {
            return false;
        }

        @Override
        public void addSettingsListener(@NotNull SettingsListener listener) {
            listeners_.add(listener);
        }

        private void BuildComponent() {
            AddLabel(new JLabel("Build system:"), 0, true);
            component_.add(build_system_, FieldConstraints(0, true));
            AddLabel(id_label_, 1, true);
            component_.add(application_id_, FieldConstraints(1, true));
            AddLabel(platform_label_, 2, true);
            component_.add(platform_options_, FieldConstraints(2, true));
            if (application_) {
                AddLabel(template_label_, 3, true);
                component_.add(template_, FieldConstraints(3, true));
            }
            AddLabel(new JLabel("HuxerUI SDK:"), 4, false);
            component_.add(sdk_, FieldConstraints(4, false));
        }

        private void AddLabel(JLabel field, int row, boolean bottom_inset) {
            GridBagConstraints label = new GridBagConstraints();
            label.gridx = 0;
            label.gridy = row;
            label.anchor = GridBagConstraints.WEST;
            label.insets = JBUI.insets(0, 0, bottom_inset ? 8 : 0, 12);
            component_.add(field, label);
        }

        private static GridBagConstraints FieldConstraints(int row, boolean bottom_inset) {
            GridBagConstraints field = new GridBagConstraints();
            field.gridx = 1;
            field.gridy = row;
            field.weightx = 1.0;
            field.fill = GridBagConstraints.HORIZONTAL;
            field.anchor = GridBagConstraints.WEST;
            field.insets = JBUI.insets(0, 0, bottom_inset ? 8 : 0, 0);
            return field;
        }

        private void SettingsChanged() {
            boolean cmake = IsCMake();
            id_label_.setVisible(cmake);
            application_id_.setVisible(cmake);
            platform_label_.setVisible(cmake);
            platform_options_.setVisible(cmake);
            template_label_.setVisible(!cmake);
            template_.setVisible(!cmake);
            boolean valid = validate() == null;
            listeners_.forEach(listener -> listener.stateChanged(valid));
            check_valid_.run();
        }

        private boolean IsCMake() {
            return build_system_.getSelectedIndex() == 0;
        }

        private List<String> SelectedPlatforms() {
            List<String> selected = new ArrayList<>();
            platforms_.forEach((platform, check_box) -> {
                if (check_box.isSelected()) {
                    selected.add(platform);
                }
            });
            return selected;
        }
    }
}
