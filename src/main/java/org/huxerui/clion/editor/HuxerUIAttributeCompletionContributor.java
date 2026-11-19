package org.huxerui.clion.editor;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public final class HuxerUIAttributeCompletionContributor extends CompletionContributor implements DumbAware {
    private static final Pattern attribute_prefix = Pattern.compile("\\[\\[(huxerui::)?([A-Za-z_]\\w*)?\\z");

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (parameters.getCompletionType() != CompletionType.BASIC) {
            return;
        }
        AttributePrefix attribute = FindPrefix(parameters.getEditor().getDocument().getCharsSequence(),
                parameters.getOffset());
        if (attribute == null) {
            return;
        }
        var position = parameters.getPosition();
        if (PsiTreeUtil.getParentOfType(position, PsiComment.class, false) != null
                || PsiTreeUtil.getParentOfType(position, PsiLanguageInjectionHost.class, false) != null
                || !HuxerUIProjectService.Get(position.getProject()).IsProject()) {
            return;
        }
        result.withPrefixMatcher(attribute.typed()).addElement(LookupElementBuilder.create(attribute.completion())
                .withTypeText("HuxerUI attribute", true)
                .withInsertHandler((context, item) -> CloseAttribute(context)));
    }

    private static void CloseAttribute(InsertionContext context) {
        var document = context.getDocument();
        CharSequence text = document.getCharsSequence();
        int tail = context.getTailOffset();
        int brackets = 0;
        while (brackets < 2 && tail + brackets < text.length() && text.charAt(tail + brackets) == ']') {
            brackets++;
        }
        if (brackets < 2) {
            document.insertString(tail + brackets, "]]".substring(brackets));
        }
        if (context.getCompletionChar() == ']') {
            context.setAddCompletionChar(false);
        }
        context.setTailOffset(tail + 2);
        context.getEditor().getCaretModel().moveToOffset(tail + 2);
    }

    static @Nullable AttributePrefix FindPrefix(CharSequence text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        var matcher = attribute_prefix.matcher(text).region(Math.max(0, offset - 64), offset);
        if (!matcher.find()) {
            return null;
        }
        String typed = matcher.group(2) == null ? "" : matcher.group(2);
        String completion = matcher.group(1) == null ? "huxerui::composable" : "composable";
        return completion.startsWith(typed) ? new AttributePrefix(typed, completion) : null;
    }

    record AttributePrefix(String typed, String completion) {}
}
