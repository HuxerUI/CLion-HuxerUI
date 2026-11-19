package org.huxerui.clion.run;

import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HuxerUIRunConfigurationTest {
    @Test
    void delegatesWebRunToTheCli() {
        assertEquals(
                List.of("run", "web", "--config", "debug"),
                HuxerUIRunConfiguration.BuildRunArguments("web", "chrome", "debug"));
    }

    @Test
    void forwardsPhysicalDeviceIdsOnlyForMobilePlatforms() {
        assertEquals(
                List.of("run", "android", "--config", "release", "--device", "device-1"),
                HuxerUIRunConfiguration.BuildRunArguments("android", "device-1", "release"));
        assertEquals(
                List.of("run", "linux", "--config", "debug"),
                HuxerUIRunConfiguration.BuildRunArguments("linux", "local", "debug"));
    }

    @Test
    void passesWatchAndBuildOptionsToTheSameRunCommand() {
        assertEquals(
                List.of("run", "android", "--config", "debug", "--abi", "x86_64", "--optimization", "size",
                        "--lto", "off", "--device", "emulator-5554", "--watch"),
                HuxerUIRunConfiguration.BuildRunArguments("android", "emulator-5554",
                        new HuxerUIBuildOptions("debug", "x86_64", "size", "off"), true));
    }

    @Test
    void selectsAnAbiFromTheAndroidDeviceOrValidatesAnExplicitChoice() {
        var emulator = new HuxerUIDevice("android", "emulator-5554", "Pixel", "ready", List.of("x86_64"));
        assertEquals("x86_64", HuxerUIRunConfiguration.MatchAndroidAbi(
                new HuxerUIBuildOptions("debug"), emulator).abis());
        assertThrows(IllegalArgumentException.class, () -> HuxerUIRunConfiguration.MatchAndroidAbi(
                new HuxerUIBuildOptions("debug", "arm64-v8a", "", ""), emulator));
    }

    @Test
    void givesEveryRunTargetAnIndependentName() {
        assertEquals(
                "HuxerUI Linux",
                DeviceSelectorAction.ConfigurationName(new HuxerUIDevice(
                        "linux", "local", "This Computer", "ready")));
        assertEquals(
                "HuxerUI Web",
                DeviceSelectorAction.ConfigurationName(new HuxerUIDevice(
                        "web", "chrome", "Chrome", "ready")));
        assertEquals(
                "HuxerUI Android — Pixel 9",
                DeviceSelectorAction.ConfigurationName(new HuxerUIDevice(
                        "android", "emulator-5554", "Pixel 9", "ready")));
    }

    @Test
    void readsTheNativeCMakeApplicationIdentity() {
        assertEquals(
                new HuxerUICMakeRunConfiguration.CMakeIdentity("sample", "sample_app"),
                HuxerUICMakeRunConfiguration.ParseCMakeIdentity("""
                        project(sample VERSION 1.0 LANGUAGES CXX)
                        huxerui_add_app(sample_app SOURCES src/app.cpp)
                        """));
        assertNull(HuxerUICMakeRunConfiguration.ParseCMakeIdentity("project(sample LANGUAGES CXX)"));
    }
}
