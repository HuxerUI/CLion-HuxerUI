package org.huxerui.clion.preview;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class HuxerUIPreviewSources {
    private HuxerUIPreviewSources() {}

    static void Write(Path app, Path root, Path selected, String call, @Nullable HuxerUIPreviewCpp selected_syntax,
            ProgressIndicator indicator) throws IOException {
        Path resource_root = HuxerUIProjectService.FindResourceRoot(root, selected);
        if (resource_root == null) {
            resource_root = root;
        }
        Set<Path> sources = new LinkedHashSet<>();
        AddSources(root.resolve("src"), sources);
        if (!resource_root.equals(root)) {
            AddSources(resource_root.resolve("src"), sources);
        }
        sources.add(selected);

        StringBuilder manifest = new StringBuilder();
        AddResourceConfig(manifest, resource_root, "HUXERUI_PREVIEW_RESOURCE");
        if (!resource_root.equals(root)) {
            AddResourceConfig(manifest, root, "HUXERUI_PREVIEW_LIBRARY_RESOURCE");
        }
        manifest.append("set(HUXERUI_PREVIEW_SOURCES\n");
        for (Path source : sources) {
            indicator.checkCanceled();
            boolean is_selected = source.equals(selected);
            HuxerUIPreviewCpp syntax = selected_syntax;
            if (!is_selected || syntax == null) {
                String text = Files.readString(source);
                syntax = is_selected || text.contains("Application") || text.contains("main")
                        ? HuxerUIPreviewCpp.Parse(text) : null;
            }
            Path compiled = source;
            if (syntax != null && (is_selected || syntax.HasRegistration())) {
                String key = UUID.nameUUIDFromBytes(source.toString().getBytes(StandardCharsets.UTF_8)).toString();
                compiled = app.resolve("generated").resolve(key).resolve("source.cpp");
                String contents = "#line 1 \"" + CppPath(source) + "\"\n" + syntax.WithoutRegistrations();
                if (is_selected) {
                    contents += "\n\n#line 1 \"HuxerUI Preview Sample Call\"\nnamespace huxerui_clion_preview {\n"
                            + "huxerui::View Render() {\n    return (\n" + call + "\n    );\n}\n}\n";
                }
                WriteIfChanged(compiled, contents);
            }
            manifest.append("    ").append(CMakePath(compiled)).append('\n');
        }
        manifest.append(")\nset(HUXERUI_PREVIEW_INCLUDE_DIRS\n");
        Set<Path> includes = new LinkedHashSet<>();
        includes.add(selected.getParent());
        sources.stream().map(Path::getParent).forEach(includes::add);
        includes.add(root);
        includes.add(root.resolve("include"));
        includes.add(resource_root.resolve("include"));
        for (Path include : includes) {
            manifest.append("    ").append(CMakePath(include)).append('\n');
        }
        manifest.append(")\n");
        WriteIfChanged(app.resolve("preview-sources.cmake"), manifest.toString());
    }

    private static void AddResourceConfig(StringBuilder manifest, Path root, String prefix) throws IOException {
        var resources = HuxerUIProjectService.ReadResourceConfig(root);
        if (resources != null) {
            manifest.append("set(").append(prefix).append("_DIR ").append(CMakePath(resources.directory())).append(")\n");
            manifest.append("set(").append(prefix).append("_NAMESPACE ").append(resources.namespace()).append(")\n");
        }
    }

    private static void AddSources(Path directory, Set<Path> sources) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot >= 0 && HuxerUIPreviewCpp.IsSource(name.substring(dot + 1));
                    })
                    .sorted()
                    .forEach(sources::add);
        }
    }

    static String CMakePath(Path path) {
        String value = FileUtil.toSystemIndependentName(path.toString());
        String equals = "";
        while (value.contains("]" + equals + "]")) {
            equals += "=";
        }
        return "[" + equals + "[" + value + "]" + equals + "]";
    }

    private static String CppPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void WriteIfChanged(Path path, String contents) throws IOException {
        if (Files.isRegularFile(path) && Files.readString(path).equals(contents)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }
}
