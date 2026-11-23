package org.huxerui.clion.preview;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class HuxerUIPreviewToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final String window_id = "HuxerUI Preview";

    @Override
    public @NotNull Object isApplicableAsync(@NotNull Project project,
            @NotNull Continuation<? super Boolean> continuation) {
        return HuxerUIProjectService.Get(project).IsCMakeProject();
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow tool_window) {
        Panel panel = new Panel(project);
        var content = tool_window.getContentManager().getFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        tool_window.getContentManager().addContent(content);
    }

    static void Show(Project project, VirtualFile source, HuxerUIPreviewCpp.Function function) {
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        ToolWindow window = manager.getToolWindow(window_id);
        if (window == null) {
            window = manager.registerToolWindow(window_id, task -> {
                task.anchor = ToolWindowAnchor.RIGHT;
                task.icon = HuxerUIPreviewIcons.view;
                return Unit.INSTANCE;
            });
            new HuxerUIPreviewToolWindowFactory().createToolWindowContent(project, window);
            ToolWindow registered = window;
            Disposer.register(project.getService(HuxerUIPreviewEditorListener.Lifetime.class), () -> {
                if (!project.isDisposed() && manager.getToolWindow(window_id) == registered) {
                    registered.remove();
                }
            });
        }
        ToolWindow target = window;
        window.activate(() -> {
            var content = target.getContentManager().getContent(0);
            if (content != null && content.getComponent() instanceof Panel panel) {
                panel.Select(source, function);
            }
        });
    }

    static final class Panel extends JBPanel<Panel> implements Disposable {
        private final Project project_;
        private final HuxerUIPreviewBuild build_;
        private final Path root_;
        private final ComboBox<SizePreset> sizes_ = new ComboBox<>(new SizePreset[]{
                new SizePreset("Phone", HuxerUIPreviewSize.phone),
                new SizePreset("Large Phone", new HuxerUIPreviewSize(430, 932)),
                new SizePreset("Tablet", new HuxerUIPreviewSize(768, 1024)),
                new SizePreset("Desktop", new HuxerUIPreviewSize(1280, 800)),
                new SizePreset("Custom…", null)
        });
        private final JBTextField width_ = new JBTextField(4);
        private final JBTextField height_ = new JBTextField(4);
        private final JPanel custom_row_ = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        private final JBLabel signature_ = new JBLabel("", SwingConstants.TRAILING);
        private final JBLabel sample_label_ = new JBLabel("Sample call");
        private final JBLabel status_ = new JBLabel();
        private final JBTextField sample_ = new JBTextField();
        private final JPanel sample_row_ = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        private final JBTextArea diagnostics_ = new JBTextArea(5, 0);
        private final JBScrollPane diagnostic_pane_ = new JBScrollPane(diagnostics_);
        private final ActionLink output_ = new ActionLink("Show output",
                (ActionListener) event -> ShowOutput(!diagnostic_pane_.isVisible()));
        private final JPanel status_row_ = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        private final Timer refresh_timer_ = new Timer(250, event -> Start());
        private final JBPanelWithEmptyText canvas_ = new JBPanelWithEmptyText() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (frame_ == null) {
                    return;
                }
                var insets = getInsets();
                int available_width = getWidth() - insets.left - insets.right;
                int available_height = getHeight() - insets.top - insets.bottom;
                if (available_width <= 0 || available_height <= 0) {
                    return;
                }
                double scale = Math.min(1.0, Math.min((double) available_width / frame_.getWidth(),
                        (double) available_height / frame_.getHeight()));
                int width = Math.max(1, (int) (frame_.getWidth() * scale));
                int height = Math.max(1, (int) (frame_.getHeight() * scale));
                int x = insets.left + (available_width - width) / 2;
                int y = insets.top + (available_height - height) / 2;
                var drawing = (Graphics2D) graphics.create();
                try {
                    drawing.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    drawing.drawImage(frame_, x, y, width, height, null);
                    drawing.setColor(JBColor.border());
                    drawing.drawRect(x - 1, y - 1, width + 1, height + 1);
                } finally {
                    drawing.dispose();
                }
            }
        };

        private BufferedImage frame_;
        private VirtualFile source_;
        private HuxerUIPreviewCpp.Function function_;
        private RangeMarker function_anchor_;
        private @Nullable String custom_call_;
        private long function_revision_ = -1;
        private HuxerUIPreviewSize size_ = HuxerUIPreviewSize.phone;
        private @Nullable HuxerUIPreviewSize custom_size_;
        private long build_revision_;
        private volatile long requested_;
        private long started_;
        private boolean active_;
        private volatile boolean disposed_;
        private volatile ProgressIndicator indicator_;

        Panel(Project project) {
            super(new BorderLayout());
            project_ = project;
            build_ = new HuxerUIPreviewBuild(project);
            root_ = Path.of(Objects.requireNonNull(project.getBasePath()));
            var refresh = new DumbAwareAction("Refresh Preview", "Rebuild and render the current View",
                    HuxerUIPreviewIcons.refresh) {
                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }

                @Override
                public void update(@NotNull AnActionEvent event) {
                    event.getPresentation().setEnabled(source_ != null);
                }

                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    if (sample_.isVisible()) {
                        ApplySample();
                    } else {
                        RequestRefresh(true);
                    }
                }
            };
            var edit_sample = new DumbAwareToggleAction("Edit Sample Call", "Show the sample call editor",
                    HuxerUIPreviewIcons.arguments) {
                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }

                @Override
                public void update(@NotNull AnActionEvent event) {
                    super.update(event);
                    event.getPresentation().setEnabled(source_ != null);
                }

                @Override
                public boolean isSelected(@NotNull AnActionEvent event) {
                    return sample_.isVisible();
                }

                @Override
                public void setSelected(@NotNull AnActionEvent event, boolean selected) {
                    ShowSampleEditor(selected);
                    if (selected) {
                        sample_.requestFocusInWindow();
                    }
                }
            };
            var clear = new DumbAwareAction("Clear Preview", "Clear the current View and stop refreshing on save",
                    AllIcons.Actions.GC) {
                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }

                @Override
                public void update(@NotNull AnActionEvent event) {
                    event.getPresentation().setEnabled(source_ != null);
                }

                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    ClearPreview();
                }
            };
            var toolbar = ActionManager.getInstance().createActionToolbar("HuxerUI.PreviewToolbar",
                    new DefaultActionGroup(edit_sample, refresh, clear), true);
            toolbar.setTargetComponent(this);
            toolbar.setReservePlaceAutoPopupIcon(false);
            toolbar.getComponent().setBorder(JBUI.Borders.empty());
            var controls = BuildSizeControls(toolbar.getComponent());

            var code_font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().getSize());
            sample_.setFont(code_font);
            sample_.setToolTipText("Press Enter to render the sample call");
            sample_.addActionListener(event -> ApplySample());
            diagnostics_.setFont(code_font);
            diagnostics_.setEditable(false);
            diagnostics_.setLineWrap(true);
            diagnostic_pane_.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
            diagnostic_pane_.setVisible(false);
            output_.setVisible(false);
            status_.setFont(JBUI.Fonts.smallFont());
            status_.setForeground(JBColor.lazy(UIUtil::getContextHelpForeground));
            status_row_.setBorder(JBUI.Borders.empty(4, 10));
            status_row_.add(status_, BorderLayout.CENTER);
            status_row_.add(output_, BorderLayout.EAST);
            status_row_.setVisible(false);
            JPanel footer = new JPanel(new BorderLayout());
            footer.add(status_row_, BorderLayout.NORTH);
            footer.add(diagnostic_pane_, BorderLayout.CENTER);

            sample_label_.setLabelFor(sample_);
            signature_.setFont(JBUI.Fonts.smallFont());
            signature_.setForeground(JBColor.lazy(UIUtil::getContextHelpForeground));
            var sample_caption = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            sample_caption.add(sample_label_, BorderLayout.WEST);
            sample_caption.add(signature_, BorderLayout.CENTER);
            sample_row_.add(sample_caption, BorderLayout.NORTH);
            sample_row_.add(sample_, BorderLayout.CENTER);
            sample_row_.setVisible(false);
            ShowSampleEditor(false);
            JPanel header = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            header.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()),
                    JBUI.Borders.empty(6, 10, 8, 10)));
            header.add(controls, BorderLayout.NORTH);
            header.add(sample_row_, BorderLayout.CENTER);

            canvas_.setBackground(JBColor.lazy(() ->
                    EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground()));
            canvas_.setBorder(JBUI.Borders.empty(14));
            ShowEmptyPreview();
            add(header, BorderLayout.NORTH);
            add(canvas_, BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);

            refresh_timer_.setRepeats(false);
            addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                        && started_ != requested_) {
                    refresh_timer_.restart();
                }
            });
            var connection = ApplicationManager.getApplication().getMessageBus().connect(this);
            connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
                    var paths = events.stream().filter(event -> event.getFileSystem() instanceof LocalFileSystem)
                            .map(event -> Path.of(event.getPath())).toList();
                    if (!paths.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (source_ != null && paths.stream().anyMatch(path -> path.equals(source_.toNioPath())
                                    || Watches(root_, path))) {
                                RequestRefresh(true);
                            }
                        });
                    }
                }
            });
        }

        private JPanel BuildSizeControls(JComponent toolbar) {
            sizes_.getAccessibleContext().setAccessibleName("Preview size in logical pixels");
            sizes_.setToolTipText("Preview size in logical pixels");
            var toolbar_row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            toolbar_row.add(sizes_, BorderLayout.CENTER);
            toolbar_row.add(toolbar, BorderLayout.EAST);

            var dimensions = new JPanel(new GridLayout(1, 2, JBUI.scale(6), 0));
            dimensions.add(SizeField("Width", width_));
            dimensions.add(SizeField("Height", height_));
            var apply = new JButton("Apply");
            var inputs = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            inputs.add(dimensions, BorderLayout.CENTER);
            var button = new JPanel(new BorderLayout());
            button.add(apply, BorderLayout.SOUTH);
            inputs.add(button, BorderLayout.EAST);
            custom_row_.add(inputs, BorderLayout.CENTER);

            var validation = new JBLabel("Enter whole numbers from 1 to " + HuxerUIPreviewSize.max_dimension + ".");
            validation.setForeground(JBColor.RED);
            validation.setVisible(false);
            custom_row_.add(validation, BorderLayout.SOUTH);
            custom_row_.setVisible(false);
            ActionListener apply_size = event -> {
                var size = HuxerUIPreviewSize.Parse(width_.getText(), height_.getText());
                validation.setVisible(size == null);
                if (size != null) {
                    custom_size_ = size;
                    SetSize(size);
                }
            };
            apply.addActionListener(apply_size);
            width_.addActionListener(apply_size);
            height_.addActionListener(apply_size);

            sizes_.addActionListener(event -> {
                var selection = (SizePreset) sizes_.getSelectedItem();
                if (selection == null) {
                    return;
                }
                var preset = selection.size();
                custom_row_.setVisible(preset == null);
                validation.setVisible(false);
                if (preset == null) {
                    preset = custom_size_ == null ? size_ : custom_size_;
                    width_.setText(Integer.toString(preset.width()));
                    height_.setText(Integer.toString(preset.height()));
                }
                SetSize(preset);
            });

            var controls = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            controls.add(toolbar_row, BorderLayout.NORTH);
            controls.add(custom_row_, BorderLayout.CENTER);
            return controls;
        }

        private static JPanel SizeField(String name, JBTextField input) {
            var label = new JBLabel(name);
            label.setLabelFor(input);
            var field = new JPanel(new BorderLayout(0, JBUI.scale(2)));
            field.add(label, BorderLayout.NORTH);
            field.add(input, BorderLayout.CENTER);
            return field;
        }

        private void SetSize(HuxerUIPreviewSize size) {
            if (!size.equals(size_)) {
                size_ = size;
                RequestRefresh(false);
            }
        }

        private void ClearPreview() {
            if (source_ == null) {
                return;
            }
            refresh_timer_.stop();
            started_ = ++requested_;
            source_ = null;
            function_ = null;
            custom_call_ = null;
            function_anchor_.dispose();
            function_anchor_ = null;
            frame_ = null;
            CancelActiveBuild();

            sample_.setText("");
            signature_.setText("");
            signature_.setToolTipText(null);
            ShowSampleEditor(false);
            sample_row_.setVisible(false);
            diagnostics_.setText("");
            ClearOutput();
            ShowEmptyPreview();
        }

        private void ShowEmptyPreview() {
            ShowStatus("Select a View to preview");
            canvas_.getEmptyText().appendLine("Click its gutter icon in the editor.");
        }

        void Select(VirtualFile source, HuxerUIPreviewCpp.Function function) {
            if (!source.equals(source_) || !function.name().equals(function_.name())
                    || !function_anchor_.isValid() || function_anchor_.getStartOffset() != function.offset()) {
                var document = FileDocumentManager.getInstance().getDocument(source);
                int end = function.offset() + function.name().length() - function.name().lastIndexOf("::") - 2;
                if (document == null || end > document.getTextLength()) {
                    return;
                }
                if (function_anchor_ != null) {
                    function_anchor_.dispose();
                }
                function_anchor_ = document.createRangeMarker(function.offset(), end);
                source_ = source;
                function_ = function;
                custom_call_ = null;
                frame_ = null;
                ClearOutput();
                ShowSignature(function);
                sample_.setText(function.Call());
                sample_row_.setVisible(true);
                ShowSampleEditor(function.needs_sample() || !function.arguments().isEmpty());
            }
            if (function_.PreviewCall(custom_call_) == null) {
                ShowSampleEditor(true);
                ShowStatus("Enter a sample call");
                canvas_.getEmptyText().appendLine("Press Enter to render.");
                sample_.requestFocusInWindow();
                String text = sample_.getText();
                int placeholder = text.indexOf("/* ");
                int placeholder_end = text.indexOf(" */", placeholder);
                if (placeholder >= 0 && placeholder_end >= 0) {
                    sample_.select(placeholder, placeholder_end + 3);
                } else {
                    sample_.setCaretPosition(Math.max(0, text.length() - 1));
                }
            } else if (frame_ == null) {
                ShowStatus("Rendering preview…");
            }
            RequestRefresh(true);
        }

        private void ShowSignature(HuxerUIPreviewCpp.Function function) {
            String signature = function.name().substring(2) + function.signature();
            signature_.setText(signature);
            signature_.setToolTipText(signature);
        }

        private void UpdateFunction(HuxerUIPreviewCpp.Function updated, long revision) {
            if (custom_call_ == null && sample_.getText().equals(function_.Call())) {
                sample_.setText(updated.Call());
            }
            if (function_.arguments().isEmpty() && !updated.arguments().isEmpty()) {
                ShowSampleEditor(true);
            }
            function_ = updated;
            function_revision_ = revision;
            ShowSignature(updated);
        }

        private void ShowSampleEditor(boolean visible) {
            sample_label_.setVisible(visible);
            sample_.setVisible(visible);
            signature_.setHorizontalAlignment(visible ? SwingConstants.TRAILING : SwingConstants.LEADING);
        }

        private void ShowStatus(String message) {
            boolean has_frame = frame_ != null;
            status_.setIcon(null);
            status_.setText(has_frame ? message : "");
            status_row_.setVisible(has_frame || output_.isVisible());
            canvas_.getEmptyText().setText(has_frame ? "" : message);
            canvas_.repaint();
        }

        private void ShowOutput(boolean visible) {
            diagnostic_pane_.setVisible(visible);
            output_.setText(visible ? "Hide output" : "Show output");
            if (visible) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed_ && diagnostic_pane_.isShowing()) {
                        var scrollbar = diagnostic_pane_.getVerticalScrollBar();
                        scrollbar.setValue(scrollbar.getMaximum());
                    }
                });
            }
        }

        private void ClearOutput() {
            output_.setVisible(false);
            ShowOutput(false);
        }

        private void ApplySample() {
            if (source_ == null) {
                return;
            }
            String call = sample_.getText().strip();
            custom_call_ = call.equals(function_.Call()) ? null : call;
            if (call.isEmpty()) {
                ClearOutput();
                ShowStatus("Enter a sample call");
            }
            RequestRefresh(true);
        }

        private void RequestRefresh(boolean rebuild) {
            if (!disposed_ && source_ != null) {
                if (rebuild) {
                    ++build_revision_;
                }
                ++requested_;
                if (isShowing()) {
                    refresh_timer_.restart();
                }
            }
        }

        private void Start() {
            if (disposed_ || active_ || started_ == requested_ || !isShowing()) {
                return;
            }
            long revision = requested_;
            started_ = revision;
            if (source_ == null) {
                return;
            }
            if (!source_.isValid() || !function_anchor_.isValid()) {
                ClearOutput();
                ShowStatus("Select the View again");
                return;
            }
            var document = function_anchor_.getDocument();
            boolean source_saved = !FileDocumentManager.getInstance().isDocumentUnsaved(document);
            long build_revision = build_revision_;
            var snapshot = source_saved && build_revision != function_revision_
                    ? document.getImmutableCharSequence() : null;
            int function_offset = function_anchor_.getStartOffset();
            var function = function_;
            String custom_call = custom_call_;
            Path source = source_.toNioPath();
            var size = size_;
            active_ = true;
            ClearOutput();
            ShowStatus("Rendering preview…");

            new Task.Backgroundable(project_, "Render HuxerUI Preview", true) {
                private BufferedImage image_;
                private Exception error_;
                private HuxerUIPreviewCpp.Function resolved_;
                private String sample_call_;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator_ = indicator;
                    try {
                        if (!disposed_ && revision == requested_) {
                            var syntax = snapshot == null ? null : HuxerUIPreviewCpp.Parse(snapshot.toString());
                            resolved_ = syntax == null ? function : syntax.FindFunction(function.name(), function_offset);
                            if (resolved_ != null) {
                                sample_call_ = resolved_.PreviewCall(custom_call);
                                if (sample_call_ != null) {
                                    var inputs = new HuxerUIPreviewBuild.Inputs(source, sample_call_, build_revision);
                                    image_ = build_.Render(inputs, size, source_saved, syntax, indicator);
                                }
                            }
                        }
                    } catch (ProcessCanceledException error) {
                        throw error;
                    } catch (Exception error) {
                        error_ = error;
                    } finally {
                        indicator_ = null;
                    }
                }

                @Override
                public void onSuccess() {
                    if (disposed_ || revision != requested_) {
                        return;
                    }
                    if (resolved_ != null && snapshot != null) {
                        UpdateFunction(resolved_, build_revision);
                    }
                    if (error_ == null) {
                        if (resolved_ == null) {
                            ShowStatus("Select the View again");
                        } else if (sample_call_ == null) {
                            ShowSampleEditor(true);
                            ShowStatus("Enter a sample call");
                        } else if (image_ == null) {
                            ShowStatus("Save the source to refresh");
                        } else {
                            frame_ = image_;
                            ShowStatus(size + " logical px");
                        }
                        return;
                    }
                    String message = error_.getMessage();
                    diagnostics_.setText(message == null ? error_.toString() : message);
                    diagnostics_.setCaretPosition(diagnostics_.getDocument().getLength());
                    output_.setVisible(true);
                    ShowStatus("Preview failed");
                    if (frame_ != null) {
                        status_.setIcon(AllIcons.General.Error);
                    }
                    ShowOutput(frame_ == null);
                }

                @Override
                public void onCancel() {
                    if (!disposed_ && revision == requested_) {
                        ShowStatus("Preview canceled");
                    }
                }

                @Override
                public void onFinished() {
                    active_ = false;
                    if (!disposed_ && isShowing() && started_ != requested_) {
                        refresh_timer_.restart();
                    }
                }
            }.queue();
        }

        @Override
        public void dispose() {
            disposed_ = true;
            refresh_timer_.stop();
            if (function_anchor_ != null) {
                function_anchor_.dispose();
            }
            CancelActiveBuild();
            ApplicationManager.getApplication().executeOnPooledThread(build_::Close);
        }

        private void CancelActiveBuild() {
            ProgressIndicator indicator = indicator_;
            if (indicator != null) {
                indicator.cancel();
            }
        }
    }

    static boolean Watches(Path root, Path file) {
        if (!file.startsWith(root) || file.equals(root)) {
            return false;
        }
        Path relative = root.relativize(file);
        if (relative.startsWith(Path.of("examples", "preview"))) {
            return true;
        }
        return switch (relative.getName(0).toString()) {
            case "preview", "src", "include", "resources" -> true;
            default -> relative.getNameCount() == 1 && relative.toString().equals("CMakeLists.txt");
        };
    }

    private record SizePreset(String label, @Nullable HuxerUIPreviewSize size) {
        @Override
        public String toString() {
            return size == null ? label : label + " " + size;
        }
    }
}
