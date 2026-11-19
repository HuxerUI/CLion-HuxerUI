package org.huxerui.clion.resources;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.jetbrains.rdclient.actions.ActionCallStrategy;
import com.jetbrains.rdclient.actions.base.BackendDelegatingActionCustomization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HuxerUINovaResourceNavigation extends BackendDelegatingActionCustomization {
    @Override
    public boolean isAvailable(@NotNull DataContext context, @Nullable FileType file_type) {
        Editor editor = CommonDataKeys.EDITOR.getData(context);
        Project project = CommonDataKeys.PROJECT.getData(context);
        if (editor == null || editor.isDisposed() || project == null || project.isDisposed()) {
            return false;
        }
        return ReadAction.compute(() -> HuxerUIResourceGotoHandler.FindTarget(
                PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()),
                editor, editor.getCaretModel().getOffset()) != null);
    }

    @Override
    public ActionCallStrategy getActionCallStrategy(@NotNull DataContext context, @NotNull String action_id) {
        return ActionCallStrategy.FrontendOnly;
    }
}
