package org.huxerui.clion.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HuxerUIProjectServiceTest {
    @Test
    void recognizesApplicationsFromTheirCMakeCommand(@TempDir Path root) throws Exception {
        WriteCMake(root, "huxerui_add_app(sample SOURCES src/custom.cpp)\n");

        assertEquals(HuxerUIProjectService.ProjectKind.APPLICATION, HuxerUIProjectService.FindProjectKind(root));
        assertEquals(root, HuxerUIProjectService.FindApplicationRoot(root));
    }

    @Test
    void recognizesLibrariesAndResolvesTheirPreviewApplication(@TempDir Path root) throws Exception {
        WriteCMake(root, "HUXERUI_ADD_LIBRARY(camera SOURCES src/camera.cpp)\n");
        Path preview = root.resolve("examples/preview");
        WriteCMake(preview, "huxerui_add_app(camera_preview SOURCES src/preview.cpp)\n");

        assertEquals(HuxerUIProjectService.ProjectKind.LIBRARY, HuxerUIProjectService.FindProjectKind(root));
        assertEquals(preview, HuxerUIProjectService.FindApplicationRoot(root));
        assertEquals(root, HuxerUIProjectService.FindResourceRoot(root, root.resolve("src/camera.cpp")));
        assertEquals(preview, HuxerUIProjectService.FindResourceRoot(root, preview.resolve("src/preview.cpp")));
    }

    @Test
    void recognizesLibrariesEvenBeforeTheyHaveAPreviewShell(@TempDir Path root) throws Exception {
        WriteCMake(root, "huxerui_add_library(camera)\n");

        assertEquals(HuxerUIProjectService.ProjectKind.LIBRARY, HuxerUIProjectService.FindProjectKind(root));
        assertNull(HuxerUIProjectService.FindApplicationRoot(root));
    }

    @Test
    void recognizesMcppSourceDependencies(@TempDir Path root) throws Exception {
        Path mcpp = Files.createDirectory(root.resolve("mcpp"));
        Files.writeString(mcpp.resolve("mcpp.toml"), """
                [dependencies]
                huxerui = { path = "/source/HuxerUI" }
                [targets.camera]
                kind = "bin"
                """);
        assertTrue(HuxerUIProjectService.McppManifest(mcpp).contains("huxerui ="));
    }

    @Test
    void ignoresCommandNamesInCommentsAndCMakeStrings(@TempDir Path root) throws Exception {
        WriteCMake(root, """
                # huxerui_add_app(commented)
                set(TEXT "huxerui_add_library(quoted)")
                #[=[ huxerui_add_app(bracket_comment) ]=]
                set(BRACKET [=[huxerui_add_library(bracket_argument)]=])
                """);

        assertEquals(HuxerUIProjectService.ProjectKind.NONE, HuxerUIProjectService.FindProjectKind(root));
    }

    @Test
    void readsLiteralResourceConfigurationFromTheHuxerUITarget(@TempDir Path root) throws Exception {
        WriteCMake(root, """
                #[=[ huxerui_add_app(fake RESOURCES wrong RESOURCE_NAMESPACE wrong) ]=]
                huxerui_add_library(widgets
                    SOURCES ${LIBRARY_SOURCE_FILES}
                    RESOURCES # Keep the default images and strings together.
                        [=[shared resources]=]
                    RESOURCE_NAMESPACE "widgets"
                )
                """);

        assertEquals(new HuxerUIProjectService.ResourceConfig(root.resolve("shared resources"), "widgets"),
                HuxerUIProjectService.ReadResourceConfig(root));
        WriteCMake(root, "huxerui_add_app(widgets RESOURCES \"\u0024{DIRECTORY}\" RESOURCE_NAMESPACE app)");
        assertNull(HuxerUIProjectService.ReadResourceConfig(root));
    }

    @Test
    void doesNotUseGeneratedCachesOrLookalikeDirectoriesForRecognition(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".huxerui/build"));
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("platform/linux"));
        Files.createDirectories(root.resolve("resources"));
        WriteCMake(root, "add_executable(sample src/app.cpp)\n");

        assertEquals(HuxerUIProjectService.ProjectKind.NONE, HuxerUIProjectService.FindProjectKind(root));
        assertNull(HuxerUIProjectService.FindApplicationRoot(root));
        assertTrue(Files.isDirectory(root.resolve(".huxerui")));
    }

    private static void WriteCMake(Path root, String content) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("CMakeLists.txt"), content);
    }
}
