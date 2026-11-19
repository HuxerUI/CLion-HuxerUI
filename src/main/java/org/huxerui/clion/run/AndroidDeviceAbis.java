package org.huxerui.clion.run;

import com.google.gson.JsonParser;
import com.intellij.execution.process.CapturingProcessHandler;
import org.huxerui.clion.cli.HuxerUICommand;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class AndroidDeviceAbis {
    private AndroidDeviceAbis() {}

    public static List<HuxerUIDevice> Load(Path home, Path cli, Path root, List<HuxerUIDevice> devices) {
        if (devices.stream().noneMatch(HuxerUIDevice::IsReady)) {
            return devices;
        }
        Path adb = FindAdb(home, cli, root);
        if (adb == null) {
            return devices;
        }
        return devices.stream().map(device -> device.IsReady()
                ? new HuxerUIDevice(device.platform(), device.id(), device.name(), device.state(),
                        ReadAbis(adb, root, device.id()))
                : device).toList();
    }

    private static @Nullable Path FindAdb(Path home, Path cli, Path root) {
        try {
            var command = HuxerUICommand.Create(home, cli, root,
                    List.of("doctor", "android", "--operation", "devices", "--json"));
            var output = new CapturingProcessHandler(command).runProcess(8000);
            if (output.isTimeout()) {
                return null;
            }
            var plan = JsonParser.parseString(output.getStdout()).getAsJsonObject();
            if (plan.get("schema").getAsInt() == 1) {
                for (var item : plan.getAsJsonArray("checks")) {
                    var check = item.getAsJsonObject();
                    if (check.get("id").getAsString().equals("android.platform_tools")
                            && check.get("status").getAsString().equals("ready") && check.has("path")) {
                        Path path = Path.of(check.get("path").getAsString());
                        return path.isAbsolute() && Files.isRegularFile(path) ? path : null;
                    }
                }
            }
        } catch (com.intellij.execution.ExecutionException | RuntimeException ignored) {
            // The SDK default remains available when the device tool cannot be resolved.
        }
        return null;
    }

    private static List<String> ReadAbis(Path adb, Path root, String device) {
        for (String property : List.of("ro.product.cpu.abilist", "ro.product.cpu.abi")) {
            try {
                var command = HuxerUICommand.LocalCommand(adb.toString(), root,
                        List.of("-s", device, "shell", "getprop", property));
                var output = new CapturingProcessHandler(command).runProcess(3000);
                if (!output.isTimeout() && output.getExitCode() == 0 && !output.getStdout().isBlank()) {
                    return Arrays.stream(output.getStdout().strip().split(",")).map(String::strip)
                            .filter(abi -> abi.matches("[a-z0-9_-]+")).toList();
                }
            } catch (com.intellij.execution.ExecutionException ignored) {
                return List.of();
            }
        }
        return List.of();
    }
}
