package org.huxerui.clion.build;

import com.intellij.build.FilePosition;
import com.intellij.build.events.MessageEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record HuxerUICliDiagnostic(FilePosition position, MessageEvent.Kind kind, String message) {
    private static final Pattern compiler = Pattern.compile(
            "^(.+?):(\\d+)(?::(\\d+))?:\\s*(fatal error|error|warning|note):\\s*(.+)$");
    private static final Pattern msvc = Pattern.compile(
            "^(.+?)\\((\\d+)(?:,(\\d+))?\\)\\s*:\\s*(fatal error|error|warning|note)(?:\\s+\\w+)?\\s*:\\s*(.+)$");
    private static final Pattern cmake = Pattern.compile(
            "^CMake (Error|Warning)(?: \\([^)]+\\))? at (.+?):(\\d+)(?: \\([^)]+\\))?:\\s*(.*)$");

    static @Nullable HuxerUICliDiagnostic Parse(Path root, String line) {
        String text = line.strip();
        for (Pattern pattern : new Pattern[]{compiler, msvc}) {
            Matcher match = pattern.matcher(text);
            if (match.matches()) {
                return Create(root, match.group(1), match.group(2), match.group(3), match.group(4), match.group(5));
            }
        }
        Matcher match = cmake.matcher(text);
        if (match.matches()) {
            String message = match.group(4);
            return Create(root, match.group(2), match.group(3), null, match.group(1),
                    message.isBlank() ? "CMake " + match.group(1).toLowerCase(Locale.ROOT) : message);
        }
        return null;
    }

    private static @Nullable HuxerUICliDiagnostic Create(
            Path root, String file, String row, @Nullable String column, String severity, String message) {
        try {
            int line = Integer.parseInt(row) - 1;
            if (line < 0) {
                return null;
            }
            Path path = root.resolve(file).normalize();
            int offset = column == null ? 0 : Math.max(0, Integer.parseInt(column) - 1);
            MessageEvent.Kind kind = switch (severity.toLowerCase(Locale.ROOT)) {
                case "warning" -> MessageEvent.Kind.WARNING;
                case "note" -> MessageEvent.Kind.INFO;
                default -> MessageEvent.Kind.ERROR;
            };
            return new HuxerUICliDiagnostic(new FilePosition(path.toFile(), line, offset), kind, message);
        } catch (InvalidPathException | NumberFormatException ignored) {
            return null;
        }
    }
}
