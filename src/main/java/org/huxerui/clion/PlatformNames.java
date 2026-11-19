package org.huxerui.clion;

public final class PlatformNames {
    private PlatformNames() {}

    public static String DisplayName(String platform) {
        return switch (platform) {
            case "ios" -> "iOS";
            case "macos" -> "macOS";
            case "mcpp" -> "mcpp";
            default -> Character.toUpperCase(platform.charAt(0)) + platform.substring(1);
        };
    }
}
