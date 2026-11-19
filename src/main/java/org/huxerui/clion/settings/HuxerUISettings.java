package org.huxerui.clion.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@State(name = "HuxerUISettings", storages = @Storage("HuxerUI.xml"))
public final class HuxerUISettings implements PersistentStateComponent<HuxerUISettings.StateData> {
    public static final class StateData {
        public String sdk_home = "";
    }

    private StateData state_ = new StateData();

    public static HuxerUISettings getInstance() {
        return ApplicationManager.getApplication().getService(HuxerUISettings.class);
    }

    @Override
    public @Nullable StateData getState() {
        return state_;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        XmlSerializerUtil.copyBean(state, state_);
    }

    public String GetSdkHome() {
        return state_.sdk_home;
    }

    public void SetSdkHome(String sdk_home) {
        state_.sdk_home = sdk_home == null ? "" : sdk_home.trim();
    }

    public @Nullable Path GetValidSdkHome() {
        return SdkLayout.ValidHome(state_.sdk_home);
    }

    public @Nullable Path ResolveSdkHome(@Nullable String project_path) {
        if (!state_.sdk_home.isBlank()) {
            return GetValidSdkHome();
        }
        Path environment = SdkLayout.ValidHome(EnvironmentUtil.getValue("HUXERUI_HOME"));
        if (environment != null) {
            return environment;
        }
        List<Path> installed = InstalledSdks();
        if (installed.size() == 1) {
            return installed.getFirst();
        }
        if (installed.isEmpty() && project_path != null) {
            Path parent = Path.of(project_path).toAbsolutePath().getParent();
            if (parent != null) {
                Path checkout = parent.resolve("HuxerUI");
                if (SdkLayout.IsSource(checkout) && SdkLayout.FindCli(checkout) != null) {
                    return checkout;
                }
            }
        }
        return null;
    }

    public static Path InstallDirectory() {
        return Path.of(System.getProperty("user.home"), ".huxerui", "sdk");
    }

    public static List<Path> InstalledSdks() {
        var installed = new LinkedHashSet<Path>();
        String user_home = System.getProperty("user.home");
        Path default_home = switch (HostOs()) {
            case "macos" -> Path.of(user_home, "Library", "Developer", "HuxerUI");
            case "windows" -> Path.of(user_home, "AppData", "Local", "HuxerUI");
            default -> Path.of(user_home, ".local", "share", "HuxerUI");
        };
        if (SdkLayout.IsValid(default_home) && SdkLayout.FindCli(default_home) != null) {
            installed.add(default_home);
        }
        AddVersionedSdks(installed, default_home.resolveSibling(default_home.getFileName() + "-versions"));
        AddVersionedSdks(installed, InstallDirectory());
        return List.copyOf(installed);
    }

    private static void AddVersionedSdks(Set<Path> installed, Path parent) {
        if (Files.isDirectory(parent)) {
            try (var directories = Files.list(parent)) {
                directories.filter(path -> !path.getFileName().toString().startsWith("."))
                        .filter(SdkLayout::IsValid).filter(path -> SdkLayout.FindCli(path) != null)
                        .sorted().forEach(installed::add);
            } catch (java.io.IOException ignored) {
                // The installation directory may be unavailable.
            }
        }
    }

    public List<Path> SdkCandidates(@Nullable Path parent) {
        var candidates = new LinkedHashSet<>(InstalledSdks());
        Path configured = GetValidSdkHome();
        if (configured != null) {
            candidates.add(configured);
        }
        Path environment = SdkLayout.ValidHome(EnvironmentUtil.getValue("HUXERUI_HOME"));
        if (environment != null) {
            candidates.add(environment);
        }
        String path = EnvironmentUtil.getValue("PATH");
        if (path != null) {
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                try {
                    Path directory = Path.of(entry);
                    if (directory.isAbsolute() && Files.isRegularFile(directory.resolve(SdkLayout.CliName()))) {
                        Path bin = directory.resolve(SdkLayout.CliName()).toRealPath().getParent();
                        if (bin.getFileName() != null && bin.getFileName().toString().equalsIgnoreCase("bin")) {
                            candidates.add(bin.getParent());
                        }
                    }
                } catch (java.io.IOException | InvalidPathException ignored) {
                    // A PATH entry may refer to a missing directory or executable.
                }
            }
        }
        if (parent != null) {
            for (Path anchor : new Path[]{parent, parent.getParent()}) {
                if (anchor != null) {
                    for (Path checkout : List.of(anchor, anchor.resolve("HuxerUI"))) {
                        if (SdkLayout.IsSource(checkout)) {
                            candidates.add(checkout);
                        }
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static String HostOs() {
        return org.huxerui.clion.sdk.HostPlatform.Current().os();
    }

    public boolean HasValidSdk() {
        return GetValidSdkHome() != null;
    }

    public Path RequireSdkHome() {
        Path home = GetValidSdkHome();
        if (home != null) {
            return home;
        }
        if (state_.sdk_home.isBlank()) {
            throw new IllegalStateException("HuxerUI SDK is not configured. Install one from Tools | HuxerUI.");
        }
        throw new IllegalStateException("Configured HUXERUI_HOME is not a valid SDK: " + state_.sdk_home);
    }

    public Path RequireCli() {
        Path home = RequireSdkHome();
        Path executable = SdkLayout.FindCli(home);
        if (executable == null) {
            throw new IllegalStateException("Configured HUXERUI_HOME has no built huxerui CLI: " + home);
        }
        return executable;
    }

    public static final class SdkLayout {
        private SdkLayout() {}

        public static @Nullable Path ValidHome(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                Path home = Path.of(value).toAbsolutePath().normalize();
                return IsValid(home) ? home : null;
            } catch (java.nio.file.InvalidPathException ignored) {
                return null;
            }
        }

        public static boolean IsSource(Path home) {
            return Files.isRegularFile(home.resolve("tools/huxerui_cli/CMakeLists.txt")) && IsValid(home);
        }

        public static boolean IsValid(Path home) {
            return Files.isRegularFile(home.resolve("include/huxerui/huxerui.h"))
                    && (Files.isRegularFile(home.resolve("cmake/HuxerUIApp.cmake")) || HasInstalledConfig(home));
        }

        public static String CliName() {
            return com.intellij.openapi.util.SystemInfo.isWindows
                    ? "huxerui.exe"
                    : "huxerui";
        }

        public static @Nullable Path FindCli(Path home) {
            String executable = CliName();
            if (IsSource(home)) {
                Path cached = CachedSourceCli(home);
                if (Files.isRegularFile(cached)) {
                    return cached;
                }
            }
            for (String relative : List.of(
                    "bin/" + executable,
                    "out/huxerui-cli/bin/" + executable,
                    "build/bin/" + executable,
                    "build/" + executable,
                    "cmake-build-debug/bin/" + executable,
                    "cmake-build-release/bin/" + executable,
                    "out/huxerui-cli/" + executable
            )) {
                Path candidate = home.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        public static Path CachedSourceCli(Path home) {
            String path = home.toAbsolutePath().normalize().toString();
            var key = java.util.UUID.nameUUIDFromBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Path.of(com.intellij.openapi.application.PathManager.getSystemPath(), "huxerui", "source-cli",
                    key.toString(), CliName());
        }

        private static boolean HasInstalledConfig(Path home) {
            for (String directory : List.of("lib", "lib64", "share")) {
                if (Files.isRegularFile(home.resolve(directory).resolve("cmake/HuxerUI/HuxerUIConfig.cmake"))) {
                    return true;
                }
            }
            Path library = home.resolve("lib");
            if (!Files.isDirectory(library)) {
                return false;
            }
            try (var children = Files.list(library)) {
                return children.anyMatch(path ->
                        Files.isRegularFile(path.resolve("cmake/HuxerUI/HuxerUIConfig.cmake"))
                );
            } catch (java.io.IOException ignored) {
                return false;
            }
        }
    }
}
