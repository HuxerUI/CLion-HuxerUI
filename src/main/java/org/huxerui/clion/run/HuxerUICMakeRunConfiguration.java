package org.huxerui.clion.run;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.project.Project;
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration;
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfigurationType;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jdom.Element;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HuxerUICMakeRunConfiguration {
    private static final Pattern project_expression = Pattern.compile(
            "(?mi)^\\s*project\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_.+-]*)");
    private static final Pattern target_expression = Pattern.compile(
            "(?mi)^\\s*huxerui_add_app\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_.+-]*)");

    private HuxerUICMakeRunConfiguration() {}

    static @Nullable RunnerAndConfigurationSettings Ensure(
            RunManager manager,
            Project project,
            HuxerUIDevice device
    ) {
        if (!device.id().equals("local") || !device.platform().equals(HuxerUIProjectService.HostPlatformId())) {
            return null;
        }
        Path root = HuxerUIProjectService.Get(project).ApplicationRoot();
        if (root == null) {
            return null;
        }
        CMakeIdentity identity;
        try {
            identity = ParseCMakeIdentity(Files.readString(root.resolve("CMakeLists.txt")));
        } catch (IOException error) {
            return null;
        }
        if (identity == null) {
            return null;
        }

        String name = DeviceSelectorAction.ConfigurationName(device);
        RunnerAndConfigurationSettings existing = manager.getAllSettings().stream()
                .filter(item -> item.getName().equals(name)
                        && item.getConfiguration() instanceof CMakeAppRunConfiguration)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            var configuration = (CMakeAppRunConfiguration) existing.getConfiguration();
            if (identity.target().equals(configuration.getExplicitBuildTargetName())
                    && Configure(configuration, identity, null)) {
                manager.addConfiguration(existing);
            }
            return existing;
        }

        RunnerAndConfigurationSettings created = manager.createConfiguration(
                name, CMakeAppRunConfigurationType.getInstance().getFactory());
        Configure((CMakeAppRunConfiguration) created.getConfiguration(), identity, name + " Debug");
        manager.addConfiguration(created);
        return created;
    }

    static @Nullable CMakeIdentity ParseCMakeIdentity(String cmake) {
        Matcher project = project_expression.matcher(cmake);
        Matcher target = target_expression.matcher(cmake);
        if (!project.find() || !target.find()) {
            return null;
        }
        return new CMakeIdentity(project.group(1), target.group(1));
    }

    private static boolean Configure(CMakeAppRunConfiguration configuration, CMakeIdentity identity,
            @Nullable String new_profile) {
        try {
            Element state = new Element("configuration");
            configuration.writeExternal(state);
            if (new_profile != null) {
                state.setAttribute("CONFIG_NAME", new_profile);
                state.setAttribute("RUN_TARGET_PROJECT_NAME", identity.project());
                state.setAttribute("RUN_TARGET_NAME", identity.target());
            } else if (!identity.project().equals(state.getAttributeValue("RUN_TARGET_PROJECT_NAME"))
                    || !identity.target().equals(state.getAttributeValue("RUN_TARGET_NAME"))) {
                return false;
            }
            state.setAttribute("PROJECT_NAME", identity.project());
            state.setAttribute("TARGET_NAME", identity.target());
            // CLion omits the normal build target when an explicit override is present.
            state.removeAttribute("EXPLICIT_BUILD_TARGET_NAME");
            configuration.readExternal(state);
            return true;
        } catch (com.intellij.openapi.util.InvalidDataException
                 | com.intellij.openapi.util.WriteExternalException ignored) {
            return false;
        }
    }

    record CMakeIdentity(String project, String target) {}
}
