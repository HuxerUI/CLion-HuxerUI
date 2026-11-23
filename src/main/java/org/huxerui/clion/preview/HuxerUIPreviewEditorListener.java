package org.huxerui.clion.preview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HuxerUIPreviewEditorListener implements EditorFactoryListener {
    private static final Logger log = Logger.getInstance(HuxerUIPreviewEditorListener.class);
    private static final Key<Markers> preview_markers = Key.create("HuxerUI.Preview.Markers");
    private static volatile boolean parser_unavailable_;

    @Service(Service.Level.PROJECT)
    public static final class Lifetime implements Disposable {
        @Override
        public void dispose() {}
    }

    public static final class PreviewAtCaret extends DumbAwareAction {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            Markers markers = editor == null ? null : editor.getUserData(preview_markers);
            event.getPresentation().setEnabledAndVisible(markers != null && markers.AtCaret() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            Markers markers = editor == null ? null : editor.getUserData(preview_markers);
            if (markers != null && event.getProject() != null && markers.source_.isValid()) {
                var function = markers.AtCaret();
                if (function != null) {
                    HuxerUIPreviewToolWindowFactory.Show(event.getProject(), markers.source_, function);
                }
            }
        }
    }

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        var project = editor.getProject();
        if (project == null || project.isDefault() || editor.getEditorKind() != EditorKind.MAIN_EDITOR
                || !HuxerUIProjectService.Get(project).IsCMakeProject()) {
            return;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isInLocalFileSystem() || !HuxerUIPreviewCpp.Supports(file.getExtension())
                || project.getBasePath() == null || !file.toNioPath().startsWith(Path.of(project.getBasePath()))) {
            return;
        }
        var markers = new Markers(editor, file);
        if (!Disposer.tryRegister(project.getService(Lifetime.class), markers)) {
            Disposer.dispose(markers);
            return;
        }
        editor.putUserData(preview_markers, markers);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                markers.Schedule();
            }
        }, markers);
        markers.Schedule();
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Markers markers = editor.getUserData(preview_markers);
        if (markers != null) {
            Disposer.dispose(markers);
        }
    }

    private static final class Markers implements Disposable {
        private final Editor editor_;
        private final VirtualFile source_;
        private final Alarm alarm_ = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        private final List<RangeHighlighter> highlighters_ = new ArrayList<>();
        private List<HuxerUIPreviewCpp.Function> functions_ = List.of();
        private long stamp_ = -1;

        Markers(Editor editor, VirtualFile source) {
            editor_ = editor;
            source_ = source;
        }

        void Schedule() {
            alarm_.cancelAllRequests();
            if (!parser_unavailable_) {
                alarm_.addRequest(this::Parse, 250);
            }
        }

        private void Parse() {
            var document = editor_.getDocument();
            var snapshot = ReadAction.compute(() ->
                    new Snapshot(document.getModificationStamp(), document.getImmutableCharSequence().toString()));
            List<HuxerUIPreviewCpp.Function> functions;
            try {
                functions = HuxerUIPreviewCpp.Parse(snapshot.text()).Functions();
            } catch (LinkageError error) {
                if (!parser_unavailable_) {
                    parser_unavailable_ = true;
                    log.warn("The HuxerUI C++ preview parser is unavailable on this host.", error);
                }
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (editor_.isDisposed() || alarm_.isDisposed() || document.getModificationStamp() != snapshot.stamp()) {
                    return;
                }
                Clear();
                functions_ = functions;
                stamp_ = snapshot.stamp();
                for (var function : functions) {
                    if (!function.returns_view()) {
                        continue;
                    }
                    var highlighter = editor_.getMarkupModel().addLineHighlighter(document.getLineNumber(function.offset()),
                            HighlighterLayer.ADDITIONAL_SYNTAX, null);
                    highlighter.setGutterIconRenderer(new PreviewIcon(source_, function));
                    highlighters_.add(highlighter);
                }
            });
        }

        @Nullable HuxerUIPreviewCpp.Function AtCaret() {
            if (stamp_ != editor_.getDocument().getModificationStamp()) {
                return null;
            }
            int offset = editor_.getCaretModel().getOffset();
            return functions_.stream().filter(function -> offset >= function.start() && offset < function.end())
                    .findFirst().orElse(null);
        }

        private void Clear() {
            for (var highlighter : highlighters_) {
                highlighter.dispose();
            }
            highlighters_.clear();
        }

        @Override
        public void dispose() {
            if (editor_.getUserData(preview_markers) == this) {
                editor_.putUserData(preview_markers, null);
            }
            Clear();
        }
    }

    private static final class PreviewIcon extends GutterIconRenderer {
        private final VirtualFile source_;
        private final HuxerUIPreviewCpp.Function function_;

        PreviewIcon(VirtualFile source, HuxerUIPreviewCpp.Function function) {
            source_ = source;
            function_ = function;
        }

        @Override
        public @NotNull Icon getIcon() {
            return HuxerUIPreviewIcons.gutter;
        }

        @Override
        public String getTooltipText() {
            return "Preview " + function_.name().substring(2) + function_.signature();
        }

        @Override
        public boolean isNavigateAction() {
            return true;
        }

        @Override
        public AnAction getClickAction() {
            return DumbAwareAction.create("Preview HuxerUI View", event -> {
                var project = event.getProject();
                if (project != null && source_.isValid()) {
                    HuxerUIPreviewToolWindowFactory.Show(project, source_, function_);
                }
            });
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PreviewIcon preview && source_.equals(preview.source_)
                    && function_.equals(preview.function_);
        }

        @Override
        public int hashCode() {
            return 31 * source_.hashCode() + function_.hashCode();
        }
    }

    private record Snapshot(long stamp, String text) {}
}
