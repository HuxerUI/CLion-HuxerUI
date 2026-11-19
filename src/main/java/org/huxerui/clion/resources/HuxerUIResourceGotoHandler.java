package org.huxerui.clion.resources;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HuxerUIResourceGotoHandler implements GotoDeclarationHandler {
    private static final Pattern expression = Pattern.compile(
            "\\b(?:[A-Za-z_][A-Za-z0-9_]*\\s*::\\s*)*(images|strings|raw)\\s*::\\s*([A-Za-z_][A-Za-z0-9_]*)"
    );

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement source_element,
            int offset,
            Editor editor
    ) {
        PsiElement target = source_element == null ? null : FindTarget(source_element.getContainingFile(), editor, offset);
        return target == null ? null : new PsiElement[]{target};
    }

    static @Nullable PsiElement FindTarget(@Nullable PsiFile source_file, Editor editor, int offset) {
        if (source_file == null) {
            return null;
        }
        String language = source_file.getLanguage().getID();
        if (!language.equals("C++") && !language.equals("ObjectiveC")) {
            return null;
        }
        ResourceReference reference = FindReference(editor.getDocument().getCharsSequence(), offset);
        if (reference == null) {
            return null;
        }

        Project project = source_file.getProject();
        List<HuxerUIResourceIndex.Entry> matches =
                HuxerUIResourceIndex
                        .Find(project, reference.kind(), source_file.getVirtualFile())
                        .stream()
                        .filter(entry -> entry.identifier().equals(reference.identifier()))
                        .toList();
        HuxerUIResourceIndex.Entry entry = HuxerUIResourceIndex.SelectNavigationEntry(matches, reference.kind());
        if (entry == null) {
            return null;
        }
        PsiFile psi_file = PsiManager.getInstance(project).findFile(entry.file());
        if (psi_file == null) {
            return null;
        }
        return reference.kind().equals("strings")
                ? new HuxerUIResourceNavigationElement(psi_file, reference.identifier(), entry.offset())
                : psi_file;
    }

    static @Nullable ResourceReference FindReference(CharSequence text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        int start = Math.max(0, offset - 256);
        int end = Math.min(text.length(), offset + 128);
        Matcher matcher = expression.matcher(text).region(start, end);
        while (matcher.find()) {
            if (offset >= matcher.start(2) && offset <= matcher.end(2)) {
                return new ResourceReference(matcher.group(1), matcher.group(2));
            }
        }
        return null;
    }

    record ResourceReference(String kind, String identifier) {}
}
