package org.huxerui.clion.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.PlatformNames;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DeviceSelectorAction extends ActionGroup implements DumbAware {
    public DeviceSelectorAction() {
        super("HuxerUI Run Target", true);
        getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        HuxerUIProjectService service = project == null ? null : HuxerUIProjectService.Get(project);
        boolean visible = service != null && service.ApplicationRoot() != null;
        event.getPresentation().setVisible(visible);
        event.getPresentation().setEnabled(visible && TrustedProjects.isProjectTrusted(project)
                && service.SdkHome() != null);
        if (event.isFromActionToolbar()) {
            var presentation = event.getPresentation();
            HuxerUIDevice target = visible ? ActiveDevice(project, service.Devices()) : null;
            if (target == null) {
                presentation.setText("Run Target");
                presentation.setDescription(getTemplatePresentation().getDescription());
            } else {
                String name = IsMobile(target.platform()) && !target.name().isBlank()
                        ? target.name() : PlatformNames.DisplayName(target.platform());
                presentation.setText(StringUtil.shortenTextWithEllipsis(name, 24, 0), false);
                presentation.setDescription(target.DisplayName());
            }
        }
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent event) {
        Project project = event == null ? null : event.getProject();
        if (project == null) {
            return AnAction.EMPTY_ARRAY;
        }
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        if (event.isFromMainMenu() || event.isFromContextMenu()) {
            service.EnsureDevicesLoaded();
        }
        List<HuxerUIDevice> devices = service.Devices();
        List<AnAction> actions = new ArrayList<>();
        for (HuxerUIDevice device : devices) {
            if (!IsMobile(device.platform())) {
                actions.add(new SelectDeviceAction(project, device, device.DisplayName()));
            }
        }
        for (String platform : service.BuildablePlatforms()) {
            if (!IsMobile(platform)) {
                continue;
            }
            actions.add(Separator.create(PlatformNames.DisplayName(platform)));
            List<HuxerUIDevice> mobile_devices = devices.stream()
                    .filter(device -> device.platform().equals(platform))
                    .sorted(Comparator.comparing(HuxerUIDevice::IsReady).reversed())
                    .toList();
            if (mobile_devices.isEmpty()) {
                actions.add(new SelectDeviceAction(project,
                        new HuxerUIDevice(platform, "", "", ""), "No devices found"));
            }
            for (HuxerUIDevice device : mobile_devices) {
                actions.add(new SelectDeviceAction(project, device, device.name().isBlank() ? device.id() : device.name()));
            }
        }
        actions.add(Separator.create());
        actions.add(new RefreshDevicesAction(project));
        return actions.toArray(AnAction[]::new);
    }

    private static boolean IsMobile(String platform) {
        return platform.equals("android") || platform.equals("ios");
    }

    static HuxerUIDevice ActiveDevice(Project project, List<HuxerUIDevice> devices) {
        var selected = RunManager.getInstance(project).getSelectedConfiguration();
        if (selected == null) {
            return null;
        }
        if (selected.getConfiguration() instanceof HuxerUIRunConfiguration configuration) {
            return devices.stream().filter(device -> device.platform().equals(configuration.GetPlatform())
                            && device.id().equals(configuration.GetDeviceId()))
                    .findFirst().orElseGet(() -> configuration.GetPlatform().isBlank() ? null :
                            new HuxerUIDevice(configuration.GetPlatform(), configuration.GetDeviceId(), "", ""));
        }
        return devices.stream().filter(device -> device.id().equals("local")
                        && selected.getName().equals(ConfigurationName(device)))
                .findFirst().orElse(null);
    }

    private static final class SelectDeviceAction extends DumbAwareAction {
        private final Project project_;
        private final HuxerUIDevice device_;

        private SelectDeviceAction(Project project, HuxerUIDevice device, String text) {
            super(text + (device.IsReady() || device.state().isEmpty()
                    ? "" : " [" + device.state() + "]"));
            if (IsMobile(device.platform())) {
                getTemplatePresentation().setDescription(device.id());
            }
            project_ = project;
            device_ = device;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            if (!device_.IsReady()) {
                return;
            }
            SelectRunConfiguration(project_, device_);
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(device_.IsReady());
        }
    }

    private static final class RefreshDevicesAction extends DumbAwareAction {
        private final Project project_;

        private RefreshDevicesAction(Project project) {
            super("Refresh Devices");
            project_ = project;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            new Task.Backgroundable(project_, "Discover HuxerUI Devices", true) {
                private Exception failure_;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        HuxerUIProjectService.Get(project_).RefreshDevices();
                    } catch (Exception error) {
                        failure_ = error;
                    }
                }

                @Override
                public void onSuccess() {
                    if (failure_ != null) {
                        HuxerUINotifications.error(project_, "Device discovery failed", failure_.getMessage());
                    }
                }
            }.queue();
        }
    }

    public static void SelectRunConfiguration(Project project, HuxerUIDevice device) {
        RunManager manager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = EnsureRunConfiguration(manager, project, device);
        HuxerUIProjectService.Get(project).SelectDevice(device);
        manager.setSelectedConfiguration(settings);
    }

    public static void SyncRunConfigurations(Project project, List<HuxerUIDevice> devices) {
        RunManager manager = RunManager.getInstance(project);
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        HuxerUIDevice selected = service.SelectedDevice();
        RunnerAndConfigurationSettings current = manager.getSelectedConfiguration();
        RunnerAndConfigurationSettings preferred = null;
        HuxerUIDevice preferred_device = null;
        List<HuxerUIDevice> ready = devices.stream().filter(HuxerUIDevice::IsReady).toList();
        for (HuxerUIDevice device : ready) {
            RunnerAndConfigurationSettings settings = EnsureRunConfiguration(manager, project, device);
            if (SameTarget(device, selected)
                    || (selected.platform().isEmpty() && (device.id().equals("local") || ready.size() == 1))) {
                preferred = settings;
                preferred_device = device;
            }
        }
        if (preferred != null && (current == null
                || (selected.platform().isEmpty() && current.getConfiguration()
                        instanceof com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration))) {
            manager.setSelectedConfiguration(preferred);
            service.SelectDevice(preferred_device);
        }
    }

    static String ConfigurationName(HuxerUIDevice device) {
        String platform = PlatformNames.DisplayName(device.platform());
        return IsMobile(device.platform())
                ? "HuxerUI " + platform + " — " + (device.name().isBlank() ? device.id() : device.name())
                : "HuxerUI " + platform;
    }

    private static RunnerAndConfigurationSettings EnsureRunConfiguration(
            RunManager manager,
            Project project,
            HuxerUIDevice device
    ) {
        RunnerAndConfigurationSettings cmake = HuxerUICMakeRunConfiguration.Ensure(manager, project, device);
        if (cmake != null) {
            return cmake;
        }
        return manager.getAllSettings().stream()
                .filter(item -> item.getConfiguration() instanceof HuxerUIRunConfiguration configuration
                        && configuration.GetPlatform().equals(device.platform())
                        && configuration.GetDeviceId().equals(device.id())
                        && !configuration.IsWatch())
                .findFirst()
                .orElseGet(() -> {
                    String name = manager.suggestUniqueName(
                            ConfigurationName(device),
                            com.intellij.execution.configurations.ConfigurationTypeUtil
                                    .findConfigurationType(HuxerUIConfigurationType.class));
                    RunnerAndConfigurationSettings created = manager.createConfiguration(
                            name,
                            HuxerUIConfigurationType.class
                    );
                    HuxerUIRunConfiguration configuration = (HuxerUIRunConfiguration) created.getConfiguration();
                    configuration.SetPlatform(device.platform());
                    configuration.SetDeviceId(device.id());
                    configuration.SetProfile(HuxerUIProjectService.Get(project).Profile());
                    manager.addConfiguration(created);
                    return created;
                });
    }

    private static boolean SameTarget(HuxerUIDevice left, HuxerUIDevice right) {
        return left.platform().equals(right.platform()) && left.id().equals(right.id());
    }
}
