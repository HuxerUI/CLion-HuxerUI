package org.huxerui.clion.run;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;

public final class RunWithWatchAction extends DumbAwareAction {
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
        event.getPresentation().setEnabled(visible && TrustedProjects.isProjectTrusted(project) && !service.HasActiveTask()
                && service.SdkCli() != null && DeviceSelectorAction.ActiveDevice(project, service.Devices()) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        HuxerUIProjectService service = HuxerUIProjectService.Get(project);
        HuxerUIDevice device = DeviceSelectorAction.ActiveDevice(project, service.Devices());
        if (device == null) {
            return;
        }
        RunManager manager = RunManager.getInstance(project);
        var current = manager.getSelectedConfiguration();
        var settings = manager.createConfiguration(
                DeviceSelectorAction.ConfigurationName(device) + " (Watch)", HuxerUIConfigurationType.class);
        var configuration = (HuxerUIRunConfiguration) settings.getConfiguration();
        configuration.SetPlatform(device.platform());
        configuration.SetDeviceId(device.id());
        if (current != null && current.getConfiguration() instanceof HuxerUIRunConfiguration selected) {
            configuration.SetBuildOptions(selected.BuildOptions());
        }
        configuration.SetWatch(true);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
    }
}
