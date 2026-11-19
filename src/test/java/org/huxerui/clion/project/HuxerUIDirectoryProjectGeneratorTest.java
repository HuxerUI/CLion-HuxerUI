package org.huxerui.clion.project;

import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.execution.ParametersListUtil;
import com.jetbrains.cidr.cpp.cmake.projectWizard.generators.CLionProjectGenerator;
import org.huxerui.clion.sdk.HuxerUISdk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HuxerUIDirectoryProjectGeneratorTest {
    @Test
    void appGeneratorUsesTheHuxerUIGroupAndAcceptsAnApplicationId() {
        HuxerUIAppProjectGenerator generator = new HuxerUIAppProjectGenerator();
        assertTrue(generator instanceof CLionProjectGenerator);
        assertEquals("HuxerUI", generator.getGroupName());
        assertEquals("HuxerUI", generator.getGroupDisplayName());
        assertEquals(800, generator.getGroupOrder());
        assertEquals("HuxerUI App", generator.getName());

        HuxerUIDirectoryProjectGenerator.Peer peer =
                (HuxerUIDirectoryProjectGenerator.Peer) generator.createPeer();
        JComponent component = peer.getComponent(new TextFieldWithBrowseButton(), () -> {});
        assertSame(component, generator.getSettingsPanel());

        JBCheckBox macos = FindCheckBox(component, "huxerui.platform.macos");
        assertNotNull(macos);
        assertEquals("macOS", macos.getText());

        JBTextField application_id = FindTextField(component, "huxerui.application_id");
        assertNotNull(application_id);
        peer.sdk_.SetDiscovered(List.of(Sdk("first")), null);
        application_id.setText("dev.example.myapp");
        assertEquals("dev.example.myapp", peer.getSettings().project_id());
        assertNull(peer.validate());

        application_id.setText("Dev.Example.MyApp");
        assertNotNull(peer.validate());
    }

    @Test
    void appWebTargetFitsInANarrowWizardAndCanBeSelected() {
        var peer = new HuxerUIDirectoryProjectGenerator.Peer(true);
        JComponent component = peer.getComponent(new TextFieldWithBrowseButton(), () -> {});
        JBCheckBox web = FindCheckBox(component, "huxerui.platform.web");
        assertNotNull(web);

        Container platforms = web.getParent();
        platforms.setSize(300, platforms.getPreferredSize().height);
        platforms.doLayout();
        assertTrue(new Rectangle(platforms.getSize()).contains(web.getBounds()));

        assertFalse(web.isSelected());
        web.doClick();
        assertTrue(peer.getSettings().platforms().contains("web"));
    }

    @Test
    void libraryGeneratorIsASeparateEntryWithInteractivePlatforms() {
        HuxerUILibraryProjectGenerator generator = new HuxerUILibraryProjectGenerator();
        assertEquals("HuxerUI", generator.getGroupName());
        assertEquals("HuxerUI Library", generator.getName());

        HuxerUIDirectoryProjectGenerator.Peer peer =
                (HuxerUIDirectoryProjectGenerator.Peer) generator.createPeer();
        JComponent component = peer.getComponent(new TextFieldWithBrowseButton(), () -> {});
        assertNotNull(FindTextField(component, "huxerui.application_id"));

        JBCheckBox linux = FindCheckBox(component, "huxerui.platform.linux");
        assertNotNull(linux);
        assertTrue(peer.getSettings().platforms().isEmpty());
        linux.doClick();
        assertEquals(List.of("linux"), peer.getSettings().platforms());
        peer.sdk_.SetDiscovered(List.of(Sdk("first")), null);
        assertNull(peer.validate());
    }

    @Test
    void requiresAnSdkAndAutomaticallySelectsTheOnlyAvailableSdk() {
        var peer = new HuxerUIDirectoryProjectGenerator.Peer(true);
        assertNull(peer.getSettings().sdk());
        assertNotNull(peer.validate());
        var sdk = Sdk("first");
        peer.sdk_.SetDiscovered(List.of(sdk), null);
        assertEquals(sdk, peer.getSettings().sdk());
        assertNull(peer.validate());
    }

    @Test
    void waitsForAnExplicitChoiceAmongMultipleSdksAndKeepsItWhenDiscoveryChanges() {
        var peer = new HuxerUIDirectoryProjectGenerator.Peer(false);
        var first = Sdk("first");
        var second = Sdk("second");
        peer.sdk_.SetDiscovered(List.of(first, second), null);
        assertNull(peer.getSettings().sdk());
        assertNotNull(peer.validate());
        var choices = (JComboBox<?>) peer.sdk_.getComponent(0);
        choices.setSelectedItem(second);
        peer.sdk_.SetDiscovered(List.of(first), null);
        assertEquals(second, peer.getSettings().sdk());
        assertNull(peer.validate());
    }

    @Test
    void explainsWhyTheDetectedSdkCannotBeUsed() {
        var peer = new HuxerUIDirectoryProjectGenerator.Peer(true);
        String problem = "HuxerUI 0.4.0 found. Add SDK 0.5.0 or newer.";
        peer.sdk_.SetDiscovered(List.of(), problem);
        assertEquals(problem, peer.validate().message);
        peer.sdk_.SetDiscovered(List.of(Sdk("current")), problem);
        assertNull(peer.validate());
    }

    @Test
    void webOnlyProjectsUseAnEmscriptenProfile(@TempDir Path temporary) throws Exception {
        Path toolchain = CreateEmscriptenToolchain(temporary);

        List<HuxerUIDirectoryProjectGenerator.CMakeProfilePlan> profiles =
                HuxerUIDirectoryProjectGenerator.PlanCMakeProfiles(List.of("web"), "linux", toolchain);

        assertEquals(1, profiles.size());
        HuxerUIDirectoryProjectGenerator.CMakeProfilePlan web = profiles.get(0);
        assertTrue(web.enabled());
        assertEquals("HuxerUI Web Debug", web.name());
        assertEquals("-DCMAKE_TOOLCHAIN_FILE=" + toolchain, web.generation_options());
    }

    @Test
    void hostAndWebProjectsReceiveSeparateProfiles(@TempDir Path temporary) throws Exception {
        Path toolchain = CreateEmscriptenToolchain(temporary);

        List<HuxerUIDirectoryProjectGenerator.CMakeProfilePlan> profiles =
                HuxerUIDirectoryProjectGenerator.PlanCMakeProfiles(
                        List.of("linux", "web"), "linux", toolchain);

        assertEquals(2, profiles.size());
        assertEquals("HuxerUI Linux Debug", profiles.get(0).name());
        assertTrue(profiles.get(0).generation_options().isEmpty());
        assertEquals("HuxerUI Web Debug", profiles.get(1).name());
        assertTrue(profiles.get(1).generation_options().contains("CMAKE_TOOLCHAIN_FILE"));
    }

    @Test
    void nonHostNativeProjectsDoNotEnableTheHostCMakeProfile() {
        List<HuxerUIDirectoryProjectGenerator.CMakeProfilePlan> profiles =
                HuxerUIDirectoryProjectGenerator.PlanCMakeProfiles(
                        List.of("android", "ios", "windows"), "linux", null);

        assertEquals(1, profiles.size());
        assertEquals("HuxerUI Native Builds", profiles.get(0).name());
        assertFalse(profiles.get(0).enabled());
    }

    @Test
    void hostProfilesUseCanonicalAppleCapitalization() {
        List<HuxerUIDirectoryProjectGenerator.CMakeProfilePlan> profiles =
                HuxerUIDirectoryProjectGenerator.PlanCMakeProfiles(List.of("macos"), "macos", null);

        assertEquals("HuxerUI macOS Debug", profiles.get(0).name());
        assertTrue(profiles.get(0).enabled());
    }

    @Test
    void sdkSwitchUpdatesManagedOptionsAndPreservesCustomOptions(@TempDir Path temporary) throws Exception {
        String existing = "-DKEEP=ON -DHUXERUI_HOME=/old -DCMAKE_TOOLCHAIN_FILE=/old-toolchain";
        Path toolchain = CreateEmscriptenToolchain(temporary.resolve("with spaces"));
        var plan = HuxerUIDirectoryProjectGenerator.PlanCMakeProfiles(List.of("web"), "linux", toolchain).getFirst();
        String options = HuxerUIDirectoryProjectGenerator.SdkGenerationOptions(
                existing, "/new sdk", plan.generation_options());
        assertEquals(List.of("-DKEEP=ON", "-DHUXERUI_HOME=/new sdk", "-DCMAKE_TOOLCHAIN_FILE=" + toolchain),
                ParametersListUtil.parse(options));
    }

    private static Path CreateEmscriptenToolchain(Path temporary) throws Exception {
        Path toolchain = temporary.resolve("cmake/Modules/Platform/Emscripten.cmake");
        Files.createDirectories(toolchain.getParent());
        return Files.createFile(toolchain);
    }

    private static HuxerUISdk Sdk(String name) {
        Path home = Path.of(name);
        return new HuxerUISdk(home, home.resolve("bin/huxerui"), "0.5.0");
    }

    private static JBCheckBox FindCheckBox(Container container, String name) {
        for (Component child : container.getComponents()) {
            if (child instanceof JBCheckBox check_box && name.equals(check_box.getName())) {
                return check_box;
            }
            if (child instanceof Container nested) {
                JBCheckBox found = FindCheckBox(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JBTextField FindTextField(Container container, String name) {
        for (Component child : container.getComponents()) {
            if (child instanceof JBTextField text_field && name.equals(text_field.getName())) {
                return text_field;
            }
            if (child instanceof Container nested) {
                JBTextField found = FindTextField(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
