package org.huxerui.clion.project;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.ide.trustedProjects.TrustedProjectsListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.jetbrains.cidr.cpp.cmake.CMakeSettings;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.run.DeviceSelectorAction;
import org.huxerui.clion.run.HuxerUIDevice;
import org.huxerui.clion.sdk.HuxerUISdkActionGroup;
import org.huxerui.clion.sdk.InstallSdkAction;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public final class HuxerUIProjectStartupActivity implements StartupActivity {
    @Override
    public void runActivity(@NotNull Project project) {
        if (!TrustedProjects.isProjectTrusted(project)) {
            var connection = ApplicationManager.getApplication().getMessageBus().connect(project);
            connection.subscribe(TrustedProjectsListener.TOPIC, new TrustedProjectsListener() {
                @Override
                public void onProjectTrusted(@NotNull Project trusted) {
                    if (project == trusted) {
                        connection.disconnect();
                        Start(project);
                    }
                }
            });
            return;
        }
        Start(project);
    }

    private static void Start(Project project) {
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        Path sdk = service.ApplicationRoot() == null ? null : service.SdkHome();
        if (sdk != null
                && CMakeSettings.getInstance(project).getProfiles().stream().noneMatch(profile ->
                        sdk.toString().equals(profile.getAdditionalEnvironment().get("HUXERUI_HOME")))) {
            ConfigureAfterSdkAvailable(project);
            return;
        }
        Activate(project);
    }

    public static void Activate(Project project) {
        if (project.isDisposed() || !TrustedProjects.isProjectTrusted(project)) {
            return;
        }
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        if (!service.IsProject()) {
            return;
        }
        if (service.IsMcppProject()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    DeviceSelectorAction.SyncRunConfigurations(project, service.Devices());
                }
            });
            return;
        }
        if (service.SdkHome() == null) {
            PromptForSdk(project, service);
            return;
        }
        service.ResetSdkPrompt();
        List<HuxerUIDevice> immediate = HuxerUIProjectService.ImmediateRunDevices(
                service.EnabledPlatforms(), HuxerUIProjectService.HostPlatformId());
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                DeviceSelectorAction.SyncRunConfigurations(project, immediate);
            }
        });
        service.EnsureDevicesLoaded();
    }

    private static void PromptForSdk(Project project, HuxerUIProjectService service) {
        if (!service.MarkSdkPromptShown()) {
            return;
        }
        var notification = NotificationGroupManager.getInstance().getNotificationGroup("HuxerUI").createNotification(
                "HuxerUI SDK required",
                "This CMake project uses HuxerUI. Install an SDK to enable build, run, and device "
                        + "support.",
                NotificationType.WARNING);
        notification.addAction(NotificationAction.createSimpleExpiring(
                "Install SDK", () -> InstallSdkAction.Install(project, () -> ConfigureAfterSdkAvailable(project))));
        notification.addAction(NotificationAction.createSimple("Select SDK", () -> HuxerUISdkActionGroup.SelectSdk(project)));
        notification.notify(project);
    }

    public static void ConfigureAfterSdkAvailable(Project project) {
        if (project.isDisposed() || !TrustedProjects.isProjectTrusted(project)) {
            return;
        }
        new Task.Backgroundable(project, "Configure HuxerUI Project", false) {
            private Exception failure_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Configuring HuxerUI CMake profiles");
                try {
                    HuxerUIDirectoryProjectGenerator.ConfigureRecognizedProject(project, false);
                } catch (com.intellij.openapi.progress.ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    failure_ = error;
                }
            }

            @Override
            public void onSuccess() {
                if (failure_ != null) {
                    HuxerUINotifications.error(project, "HuxerUI project configuration failed", failure_.getMessage());
                }
                Activate(project);
            }
        }.queue();
    }
}
