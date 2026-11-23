package org.huxerui.clion.project;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.huxerui.clion.cli.HuxerUICommand;
import org.huxerui.clion.run.DeviceOutputParser;
import org.huxerui.clion.run.AndroidDeviceAbis;
import org.huxerui.clion.run.DeviceSelectorAction;
import org.huxerui.clion.run.HuxerUIDevice;
import org.huxerui.clion.settings.HuxerUISettings;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service(Service.Level.PROJECT)
@State(name = "HuxerUIProject", storages = {
        @Storage(StoragePathMacros.WORKSPACE_FILE),
        @Storage(value = "huxerui.xml", deprecated = true)
})
public final class HuxerUIProjectService implements PersistentStateComponent<HuxerUIProjectService.StateData> {
    public static final List<String> all_platforms = List.of("android", "ios", "windows", "macos", "linux", "web");
    private static final Pattern mcpp_runner =
            Pattern.compile("(?m)^\\s*(?:kind|type)\\s*=\\s*[\"'](?:app|bin|exe)[\"']");
    private static final Pattern mcpp_dependency =
            Pattern.compile("(?m)^\\s*[\"']?(?:huxerui\\.huxerui|huxerui)[\"']?\\s*=");

    enum ProjectKind { NONE, APPLICATION, LIBRARY }

    public static final class StateData {
        public String platform = "";
        public String device_id = "";
        public String device_name = "";
        public String profile = "debug";
        public String sdk_home = "";
    }

    private final Project project_;
    private StateData state_ = new StateData();
    private volatile List<HuxerUIDevice> devices_ = List.of();
    private volatile Path discovered_sdk_;
    private final AtomicBoolean refresh_scheduled_ = new AtomicBoolean();
    private final AtomicBoolean sdk_prompt_shown_ = new AtomicBoolean();
    private final AtomicBoolean task_active_ = new AtomicBoolean();

    public HuxerUIProjectService(Project project) {
        project_ = project;
    }

    public static HuxerUIProjectService Get(Project project) {
        return project.getService(HuxerUIProjectService.class);
    }

    @Override
    public @Nullable StateData getState() {
        return state_;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        XmlSerializerUtil.copyBean(state, state_);
    }

    public boolean IsProject() {
        return IsCMakeProject() || IsMcppProject();
    }

    public boolean IsCMakeProject() {
        String base_path = project_.getBasePath();
        return base_path != null && FindProjectKind(Path.of(base_path)) != ProjectKind.NONE;
    }

    public boolean IsMcppProject() {
        String base_path = project_.getBasePath();
        return base_path != null && !IsCMakeProject() && !McppManifest(Path.of(base_path)).isEmpty();
    }

    public boolean IsRunnableMcpp() {
        String base_path = project_.getBasePath();
        return base_path != null && !IsCMakeProject() && mcpp_runner.matcher(McppManifest(Path.of(base_path))).find();
    }

    static String McppManifest(Path root) {
        try {
            Path manifest = root.resolve("mcpp.toml");
            if (!Files.isRegularFile(manifest) || SdkLayout.IsSource(root)) {
                return "";
            }
            String content = Files.readString(manifest);
            if (mcpp_dependency.matcher(content).find()) {
                return content;
            }
            Path rules = root.resolve("build.mcpp");
            return Files.isRegularFile(rules) && Files.readString(rules).contains("import huxerui.rules")
                    ? content : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    public @Nullable Path ApplicationRoot() {
        String base_path = project_.getBasePath();
        return base_path == null ? null : FindApplicationRoot(Path.of(base_path));
    }

    public @Nullable Path SdkHome() {
        return state_.sdk_home.isBlank()
                ? HuxerUISettings.getInstance().ResolveSdkHome(project_.getBasePath())
                : SdkLayout.ValidHome(state_.sdk_home);
    }

    public String SdkOverride() {
        return state_.sdk_home;
    }

    public void SetSdkOverride(String home) {
        state_.sdk_home = home;
        devices_ = List.of();
        discovered_sdk_ = null;
    }

    public @Nullable Path SdkCli() {
        Path home = SdkHome();
        return home == null ? null : SdkLayout.FindCli(home);
    }

    public boolean HasActiveTask() {
        return task_active_.get();
    }

    public boolean TryStartTask() {
        return task_active_.compareAndSet(false, true);
    }

    public void FinishTask() {
        task_active_.set(false);
    }

    public List<String> EnabledPlatforms() {
        Path root = ApplicationRoot();
        if (root == null) {
            return List.of();
        }
        Path platforms = root.resolve("platform");
        List<String> result = new ArrayList<>();
        for (String platform : all_platforms) {
            if (Files.isDirectory(platforms.resolve(platform))) {
                result.add(platform);
            }
        }
        return result;
    }

    public List<String> BuildablePlatforms() {
        String host = HostPlatformId();
        return EnabledPlatforms().stream().filter(platform ->
                platform.equals(host) || platform.equals("android") || platform.equals("web")
                        || (host.equals("macos") && platform.equals("ios"))).toList();
    }

    public static @Nullable Path FindApplicationRoot(Path project_root) {
        ProjectKind root_kind = FindProjectKind(project_root);
        if (root_kind == ProjectKind.APPLICATION) {
            return project_root;
        }
        Path preview = project_root.resolve("examples/preview");
        return root_kind == ProjectKind.LIBRARY && FindProjectKind(preview) == ProjectKind.APPLICATION ? preview : null;
    }

    public static @Nullable Path FindResourceRoot(Path project_root, @Nullable Path source_file) {
        ProjectKind root_kind = FindProjectKind(project_root);
        if (root_kind == ProjectKind.APPLICATION) {
            return project_root;
        }
        if (root_kind != ProjectKind.LIBRARY) {
            return null;
        }
        Path preview = project_root.resolve("examples/preview").normalize();
        if (source_file != null && source_file.toAbsolutePath().normalize().startsWith(preview)
                && FindProjectKind(preview) == ProjectKind.APPLICATION) {
            return preview;
        }
        return project_root;
    }

    static ProjectKind FindProjectKind(Path project_root) {
        Path cmake = project_root.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(cmake)) {
            return ProjectKind.NONE;
        }
        try {
            String content = Files.readString(cmake);
            if (ContainsCMakeCommand(content, "huxerui_add_app")) {
                return ProjectKind.APPLICATION;
            }
            if (ContainsCMakeCommand(content, "huxerui_add_library")) {
                return ProjectKind.LIBRARY;
            }
            return ProjectKind.NONE;
        } catch (IOException ignored) {
            return ProjectKind.NONE;
        }
    }

    static boolean ContainsCMakeCommand(String content, String command) {
        return FindCMakeCommand(content, command) >= 0;
    }

    public record ResourceConfig(Path directory, String namespace) {}

    public static @Nullable ResourceConfig ReadResourceConfig(Path root) throws IOException {
        Path cmake = root.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(cmake)) {
            return null;
        }
        String content = Files.readString(cmake);
        int command = FindCMakeCommand(content, "huxerui_add_app");
        if (command < 0) {
            command = FindCMakeCommand(content, "huxerui_add_library");
        }
        if (command < 0) {
            return null;
        }

        List<String> arguments = CMakeArguments(content, command + 1);
        int resources = arguments.indexOf("RESOURCES") + 1;
        int namespace = arguments.indexOf("RESOURCE_NAMESPACE") + 1;
        if (resources == 0 || resources >= arguments.size() || namespace == 0 || namespace >= arguments.size()) {
            return null;
        }
        String directory = arguments.get(resources);
        String name = arguments.get(namespace);
        if (directory.isBlank() || directory.contains("$") || directory.contains(";") || directory.contains("\\")
                || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        try {
            return new ResourceConfig(root.resolve(directory).normalize(), name);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static List<String> CMakeArguments(String content, int start) {
        List<String> arguments = new ArrayList<>();
        int offset = SkipWhitespaceAndComments(content, start);
        while (offset < content.length()) {
            char character = content.charAt(offset);
            if (character == ')') {
                return arguments;
            }
            if (character == '(') {
                break;
            }
            int end;
            int bracket_equals = BracketEquals(content, offset);
            if (bracket_equals >= 0) {
                end = SkipBracket(content, offset);
                int delimiter_length = bracket_equals + 2;
                if (end - offset < 2 * delimiter_length) {
                    break;
                }
                arguments.add(content.substring(offset + delimiter_length, end - delimiter_length));
            } else if (character == '"') {
                end = SkipQuoted(content, offset);
                if (end - offset < 2) {
                    break;
                }
                arguments.add(content.substring(offset + 1, end - 1));
            } else {
                end = offset + 1;
                while (end < content.length() && !Character.isWhitespace(content.charAt(end))
                        && "()#".indexOf(content.charAt(end)) < 0) {
                    ++end;
                }
                arguments.add(content.substring(offset, end));
            }
            offset = SkipWhitespaceAndComments(content, end);
        }
        return List.of();
    }

    private static int FindCMakeCommand(String content, String command) {
        int offset = 0;
        while (offset < content.length()) {
            char character = content.charAt(offset);
            if (character == '#') {
                offset = SkipComment(content, offset);
            } else if (character == '"') {
                offset = SkipQuoted(content, offset);
            } else if (character == '[' && BracketEquals(content, offset) >= 0) {
                offset = SkipBracket(content, offset);
            } else if (IsIdentifierStart(character)) {
                int end = offset + 1;
                while (end < content.length() && IsIdentifierPart(content.charAt(end))) {
                    ++end;
                }
                String identifier = content.substring(offset, end);
                int next = SkipWhitespaceAndComments(content, end);
                if (identifier.equalsIgnoreCase(command) && next < content.length() && content.charAt(next) == '(') {
                    return next;
                }
                offset = end;
            } else {
                ++offset;
            }
        }
        return -1;
    }

    private static int SkipWhitespaceAndComments(String content, int offset) {
        int current = offset;
        while (current < content.length()) {
            if (Character.isWhitespace(content.charAt(current))) {
                ++current;
            } else if (content.charAt(current) == '#') {
                current = SkipComment(content, current);
            } else {
                break;
            }
        }
        return current;
    }

    private static int SkipComment(String content, int offset) {
        if (offset + 1 < content.length() && content.charAt(offset + 1) == '['
                && BracketEquals(content, offset + 1) >= 0) {
            return SkipBracket(content, offset + 1);
        }
        int newline = content.indexOf('\n', offset + 1);
        return newline < 0 ? content.length() : newline + 1;
    }

    private static int SkipQuoted(String content, int offset) {
        int current = offset + 1;
        while (current < content.length()) {
            if (content.charAt(current) == '\\') {
                current += 2;
            } else if (content.charAt(current) == '"') {
                return current + 1;
            } else {
                ++current;
            }
        }
        return content.length();
    }

    private static int SkipBracket(String content, int offset) {
        int equals = BracketEquals(content, offset);
        if (equals < 0) {
            return offset + 1;
        }
        String closing = "]"
                + "=".repeat(equals) + "]";
        int end = content.indexOf(closing, offset + equals + 2);
        return end < 0 ? content.length() : end + closing.length();
    }

    private static int BracketEquals(String content, int offset) {
        if (offset >= content.length() || content.charAt(offset) != '[') {
            return -1;
        }
        int current = offset + 1;
        while (current < content.length() && content.charAt(current) == '=') {
            ++current;
        }
        return current < content.length() && content.charAt(current) == '[' ? current - offset - 1 : -1;
    }

    private static boolean IsIdentifierStart(char character) {
        return character == '_' || Character.isLetter(character);
    }

    private static boolean IsIdentifierPart(char character) {
        return character == '_' || Character.isLetterOrDigit(character);
    }

    public List<HuxerUIDevice> Devices() {
        if (IsRunnableMcpp()) {
            return List.of(new HuxerUIDevice("mcpp", "", "This Computer", "ready"));
        }
        if (Objects.equals(SdkCli(), discovered_sdk_) && !devices_.isEmpty()) {
            return devices_;
        }
        return ImmediateRunDevices(EnabledPlatforms(), HostPlatformId());
    }

    boolean MarkSdkPromptShown() {
        return sdk_prompt_shown_.compareAndSet(false, true);
    }

    void ResetSdkPrompt() {
        sdk_prompt_shown_.set(false);
    }

    public void EnsureDevicesLoaded() {
        Path cli = SdkCli();
        if (cli == null || cli.equals(discovered_sdk_)
                || !com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(project_)
                || !refresh_scheduled_.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (!project_.isDisposed()) {
                    RefreshDevices();
                }
            } catch (Exception ignored) {
                // Explicit refresh reports diagnostics; automatic discovery remains non-intrusive.
            } finally {
                refresh_scheduled_.set(false);
            }
        });
    }

    public List<HuxerUIDevice> RefreshDevices() throws Exception {
        HuxerUICommand.RequireTrusted(project_);
        Path home = SdkHome();
        Path cli = home == null ? null : SdkLayout.FindCli(home);
        Path root = ApplicationRoot();
        List<String> enabled = BuildablePlatforms();
        List<HuxerUIDevice> result = new ArrayList<>(ImmediateRunDevices(enabled, HostPlatformId()));
        if (cli == null || root == null) {
            return result;
        }
        Exception failure = null;
        for (String platform : List.of("android", "ios")) {
            if (!enabled.contains(platform)) {
                continue;
            }
            try {
                var devices = DeviceOutputParser.Parse(platform,
                        HuxerUICommand.Capture(home, cli, root, List.of("devices", platform)).getStdout());
                result.addAll(platform.equals("android") ? AndroidDeviceAbis.Load(home, cli, root, devices) : devices);
            } catch (Exception error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        List<HuxerUIDevice> discovered = List.copyOf(result);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project_.isDisposed() && cli.equals(SdkCli())) {
                devices_ = discovered;
                discovered_sdk_ = cli;
                DeviceSelectorAction.SyncRunConfigurations(project_, discovered);
                com.intellij.ide.ActivityTracker.getInstance().inc();
            }
        });
        if (failure != null) {
            throw failure;
        }
        return discovered;
    }

    public void SelectDevice(HuxerUIDevice device) {
        state_.platform = device.platform();
        state_.device_id = device.id();
        state_.device_name = device.name();
    }

    public HuxerUIDevice SelectedDevice() {
        return new HuxerUIDevice(state_.platform, state_.device_id, state_.device_name, "ready");
    }

    public String Profile() {
        return state_.profile;
    }

    public void SetProfile(String profile) {
        state_.profile = profile.equals("release") ? "release" : "debug";
    }

    public static String HostPlatformId() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

    public static List<HuxerUIDevice> ImmediateRunDevices(List<String> platforms, String host_platform) {
        List<HuxerUIDevice> devices = new ArrayList<>(2);
        if (platforms.contains(host_platform)) {
            devices.add(new HuxerUIDevice(host_platform, "local", "This Computer", "ready"));
        }
        if (platforms.contains("web")) {
            devices.add(new HuxerUIDevice("web", "chrome", "Chrome", "ready"));
        }
        return List.copyOf(devices);
    }
}
