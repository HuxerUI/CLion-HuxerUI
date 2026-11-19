package org.huxerui.clion.cli;

import java.util.Arrays;
import java.util.List;

public record HuxerUIBuildOptions(String configuration, String abis, String optimization, String lto) {
    public HuxerUIBuildOptions(String configuration) {
        this(configuration, "", "", "");
    }

    public List<String> Arguments(String operation, String platform) {
        List<String> arguments = HuxerUICommand.BuildArguments(operation, platform, configuration);
        if (!abis.isBlank()) {
            List<String> requested = Arrays.stream(abis.split(",", -1)).map(String::strip).distinct().toList();
            if (!Abis(platform).containsAll(requested)) {
                throw new IllegalArgumentException("Choose a supported ABI for " + platform);
            }
            if (operation.equals("run") && platform.equals("linux") && requested.size() != 1) {
                throw new IllegalArgumentException("Choose one ABI to run a Linux application");
            }
            arguments.addAll(List.of("--abi", String.join(",", requested)));
        }
        if (!optimization.isBlank()) {
            arguments.addAll(List.of("--optimization", optimization));
        }
        if (!lto.isBlank()) {
            arguments.addAll(List.of("--lto", lto));
        }
        return arguments;
    }

    public static List<String> Abis(String platform) {
        return switch (platform) {
            case "android" -> List.of("arm64-v8a", "x86_64");
            case "ios", "macos" -> List.of("arm64", "x86_64");
            case "linux" -> List.of("aarch64", "x86_64");
            case "web" -> List.of("wasm32");
            case "windows" -> List.of("x86_64");
            default -> List.of();
        };
    }
}
