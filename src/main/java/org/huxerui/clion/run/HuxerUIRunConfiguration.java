package org.huxerui.clion.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.filters.RegexpFilter;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;

public final class HuxerUIRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    private String platform_ = "";
    private String device_id_ = "";
    private String profile_ = "debug";
    private boolean watch_;
    private String abis_ = "";
    private String optimization_ = "";
    private String lto_ = "";

    protected HuxerUIRunConfiguration(
            Project project,
            ConfigurationFactory factory,
            String name
    ) {
        super(project, factory, name);
    }

    @Override
    public @NotNull SettingsEditor<? extends HuxerUIRunConfiguration> getConfigurationEditor() {
        return new HuxerUIRunConfigurationEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws com.intellij.execution.configurations.RuntimeConfigurationException {
        HuxerUIProjectService service = HuxerUIProjectService.Get(getProject());
        if (platform_.equals("mcpp") && service.IsRunnableMcpp() && !watch_) {
            if (!com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(getProject())) {
                throw new com.intellij.execution.configurations.RuntimeConfigurationError(
                        "Trust this project in CLion before running mcpp");
            }
            return;
        }
        if (service.ApplicationRoot() == null) {
            throw new com.intellij.execution.configurations.RuntimeConfigurationError(
                    "The current project is not a HuxerUI application");
        }
        if (!service.BuildablePlatforms().contains(platform_)) {
            throw new com.intellij.execution.configurations.RuntimeConfigurationError("Select an available HuxerUI target");
        }
        try {
            HuxerUICommand.RequireTrusted(getProject());
            BuildOptions().Arguments("run", platform_);
            if (service.SdkCli() == null) {
                throw new IllegalStateException("Select a built HuxerUI SDK from Tools | HuxerUI");
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new com.intellij.execution.configurations.RuntimeConfigurationError(error.getMessage());
        }
    }

    @Override
    public @Nullable RunProfileState getState(
            @NotNull Executor executor,
            @NotNull ExecutionEnvironment environment
    ) {
        return new HuxerUICommandLineState(environment, this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws com.intellij.openapi.util.InvalidDataException {
        super.readExternal(element);
        platform_ = element.getAttributeValue("platform", "");
        device_id_ = element.getAttributeValue("device", "");
        SetProfile(element.getAttributeValue("profile", "debug"));
        watch_ = Boolean.parseBoolean(element.getAttributeValue("watch", "false"));
        abis_ = element.getAttributeValue("abis", "");
        optimization_ = element.getAttributeValue("optimization", "");
        lto_ = element.getAttributeValue("lto", "");
    }

    @Override
    public void writeExternal(@NotNull Element element) throws com.intellij.openapi.util.WriteExternalException {
        super.writeExternal(element);
        element.setAttribute("platform", platform_);
        element.setAttribute("device", device_id_);
        element.setAttribute("profile", profile_);
        if (watch_) {
            element.setAttribute("watch", "true");
        }
        if (!abis_.isBlank()) {
            element.setAttribute("abis", abis_);
        }
        if (!optimization_.isBlank()) {
            element.setAttribute("optimization", optimization_);
        }
        if (!lto_.isBlank()) {
            element.setAttribute("lto", lto_);
        }
    }

    public String GetPlatform() {
        return platform_;
    }

    public void SetPlatform(String platform) {
        platform_ = platform;
    }

    public String GetDeviceId() {
        return device_id_;
    }

    public void SetDeviceId(String device_id) {
        device_id_ = device_id;
    }

    public String GetProfile() {
        return profile_;
    }

    public void SetProfile(String profile) {
        profile_ = "release".equals(profile) ? "release" : "debug";
    }

    public boolean IsWatch() {
        return watch_;
    }

    void SetWatch(boolean watch) {
        watch_ = watch;
    }

    HuxerUIBuildOptions BuildOptions() {
        return new HuxerUIBuildOptions(profile_, abis_, optimization_, lto_);
    }

    void SetBuildOptions(HuxerUIBuildOptions options) {
        SetProfile(options.configuration());
        abis_ = options.abis();
        optimization_ = options.optimization();
        lto_ = options.lto();
    }

    private static final class HuxerUICommandLineState extends com.intellij.execution.configurations.CommandLineState {
        private final HuxerUIRunConfiguration configuration_;

        private HuxerUICommandLineState(ExecutionEnvironment environment, HuxerUIRunConfiguration configuration) {
            super(environment);
            configuration_ = configuration;
            var console = TextConsoleBuilderFactory.getInstance().createBuilder(configuration.getProject());
            console.addFilter(new RegexpFilter(configuration.getProject(),
                    "$FILE_PATH$:$LINE$:$COLUMN$: (?:fatal error|error|warning|note):.*"));
            console.addFilter(new RegexpFilter(configuration.getProject(),
                    "$FILE_PATH$\\($LINE$(?:,$COLUMN$)?\\)\\s*:\\s*(?:fatal error|error|warning|note).*"));
            setConsoleBuilder(console);
        }

        @Override
        protected @NotNull ProcessHandler startProcess() throws ExecutionException {
            Project project = configuration_.getProject();
            HuxerUIProjectService service = HuxerUIProjectService.Get(project);
            boolean mcpp = configuration_.platform_.equals("mcpp");
            Path root = mcpp ? HuxerUICommand.ProjectRoot(project) : service.ApplicationRoot();
            if (root == null) {
                throw new ExecutionException("The HuxerUI application directory is unavailable");
            }
            if (!service.TryStartTask()) {
                throw new ExecutionException("Stop the active HuxerUI session first.");
            }
            ProcessHandler process;
            try {
                HuxerUICommand.RequireTrusted(project);
                var command = mcpp
                        ? HuxerUICommand.Mcpp(root, "run", configuration_.profile_)
                        : HuxerUICommand.Create(project, root, BuildRunArguments(configuration_.platform_,
                                configuration_.device_id_, configuration_.OptionsForDevice(service), configuration_.watch_));
                if (configuration_.watch_) {
                    var watch = new KillableProcessHandler(command);
                    watch.setShouldKillProcessSoftly(true);
                    process = watch;
                } else {
                    process = new KillableColoredProcessHandler(command);
                }
            } catch (IllegalArgumentException | IllegalStateException error) {
                service.FinishTask();
                throw new ExecutionException(error.getMessage(), error);
            } catch (ExecutionException error) {
                service.FinishTask();
                throw error;
            }
            process.addProcessListener(new ProcessListener() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    service.FinishTask();
                }
            });
            return process;
        }
    }

    private HuxerUIBuildOptions OptionsForDevice(HuxerUIProjectService service) {
        HuxerUIBuildOptions options = BuildOptions();
        if (!platform_.equals("android") || device_id_.isBlank()) {
            return options;
        }
        return service.Devices().stream().filter(device -> device.platform().equals(platform_)
                        && device.id().equals(device_id_) && device.IsReady() && !device.abis().isEmpty())
                .findFirst().map(device -> MatchAndroidAbi(options, device)).orElse(options);
    }

    static HuxerUIBuildOptions MatchAndroidAbi(HuxerUIBuildOptions options, HuxerUIDevice device) {
        String abi = PreferredAndroidAbi(options, device);
        return options.abis().isBlank()
                ? new HuxerUIBuildOptions(options.configuration(), abi, options.optimization(), options.lto()) : options;
    }

    static String PreferredAndroidAbi(HuxerUIBuildOptions options, HuxerUIDevice device) {
        List<String> requested = options.abis().isBlank() ? HuxerUIBuildOptions.Abis("android")
                : Arrays.stream(options.abis().split(",")).map(String::strip).toList();
        return device.abis().stream().filter(requested::contains).findFirst().orElseThrow(
                () -> new IllegalArgumentException("No compatible Android ABI for " + device.DisplayName()));
    }

    static List<String> BuildRunArguments(String platform, String device_id, String profile) {
        return BuildRunArguments(platform, device_id, new HuxerUIBuildOptions(profile), false);
    }

    static List<String> BuildRunArguments(
            String platform, String device_id, HuxerUIBuildOptions options, boolean watch) {
        List<String> arguments = options.Arguments("run", platform);
        if ((platform.equals("android") || platform.equals("ios")) && !device_id.isBlank()) {
            arguments.add("--device");
            arguments.add(device_id);
        }
        if (watch) {
            arguments.add("--watch");
        }
        return List.copyOf(arguments);
    }
}
