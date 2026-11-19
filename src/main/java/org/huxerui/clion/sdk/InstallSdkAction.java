package org.huxerui.clion.sdk;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.huxerui.clion.HuxerUINotifications;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.project.HuxerUIProjectStartupActivity;
import org.huxerui.clion.settings.HuxerUISettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class InstallSdkAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Install(event.getProject(), "", null);
    }

    public static void Install(Project project, Runnable on_installed) {
        Install(project, "", on_installed);
    }

    static void Install(Project project, String release, Runnable on_installed) {
        new Task.Modal(project, "Install HuxerUI SDK", true) {
            private Path installed_;
            private Exception failure_;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    installed_ = new SdkInstaller().Install(release, indicator);
                } catch (com.intellij.openapi.progress.ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    failure_ = error;
                }
            }

            @Override
            public void onSuccess() {
                if (failure_ != null) {
                    HuxerUINotifications.error(project, "HuxerUI SDK installation failed", failure_.getMessage());
                    return;
                }
                boolean project_sdk = project != null && !project.isDisposed()
                        && HuxerUIProjectService.Get(project).IsCMakeProject();
                if (project_sdk) {
                    HuxerUIProjectService.Get(project).SetSdkOverride(installed_.toString());
                } else {
                    HuxerUISettings.getInstance().SetSdkHome(installed_.toString());
                }
                HuxerUINotifications.info(project, "HuxerUI SDK installed", "HUXERUI_HOME is now " + installed_);
                if (on_installed != null) {
                    on_installed.run();
                } else if (project_sdk) {
                    HuxerUIProjectStartupActivity.ConfigureAfterSdkAvailable(project);
                }
            }
        }.queue();
    }
}
