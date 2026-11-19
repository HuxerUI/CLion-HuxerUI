package org.huxerui.clion.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HostPlatformTest {
    @Test
    void usesPublishedNamesForEachHostArchive() {
        assertEquals("huxerui-sdk-0.5.0-windows-x86_64.zip",
                new HostPlatform("windows", "x86_64", ".zip").SdkArchiveName("0.5.0"));
        assertEquals("huxerui-sdk-0.5.0-macos-arm64.tar.gz",
                new HostPlatform("macos", "arm64", ".tar.gz").SdkArchiveName("0.5.0"));
        assertEquals("huxerui-sdk-0.5.0-linux-aarch64.tar.gz",
                new HostPlatform("linux", "aarch64", ".tar.gz").SdkArchiveName("0.5.0"));
    }
}
