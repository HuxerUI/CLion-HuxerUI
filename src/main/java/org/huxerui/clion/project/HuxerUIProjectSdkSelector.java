package org.huxerui.clion.project;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;
import org.huxerui.clion.sdk.HuxerUISdk;
import org.huxerui.clion.sdk.SdkInstaller;
import org.huxerui.clion.sdk.SourceCliBuild;
import org.huxerui.clion.settings.HuxerUISettings;
import org.huxerui.clion.settings.HuxerUISettings.SdkLayout;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class HuxerUIProjectSdkSelector extends JPanel {
    private static final Logger log = Logger.getInstance(HuxerUIProjectSdkSelector.class);

    private final ComboBox<HuxerUISdk> choices_ = new ComboBox<>();
    private final JButton add_ = new JButton("Add SDK");
    private final Runnable changed_;
    private List<HuxerUISdk> discovered_ = List.of();
    private @Nullable String detection_problem_;
    private HuxerUISdk explicit_;
    private Path search_parent_;
    private boolean search_started_;
    private int search_revision_;
    private boolean searching_;
    private boolean updating_;
    private volatile boolean disposed_;

    HuxerUIProjectSdkSelector(Runnable changed) {
        super(new BorderLayout(JBUI.scale(6), 0));
        changed_ = changed;
        choices_.setName("huxerui.sdk");
        choices_.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof HuxerUISdk sdk) {
                    String label = "HuxerUI " + sdk.version();
                    setText(index < 0 ? label : label + " — " + sdk.home());
                } else {
                    setText(searching_ ? "Detecting SDKs…" : "Select an SDK");
                }
                return this;
            }
        });
        choices_.setPrototypeDisplayValue(new HuxerUISdk(Path.of("."), Path.of("."), "0.5.0"));
        choices_.addActionListener(event -> {
            if (!updating_) {
                explicit_ = Selection();
                SelectionChanged();
            }
        });
        add_.addActionListener(event -> ShowAddMenu());
        add(choices_, BorderLayout.CENTER);
        add(add_, BorderLayout.EAST);
    }

    @Nullable HuxerUISdk Selection() {
        return choices_.getItem();
    }

    @Nullable ValidationInfo Validate() {
        if (searching_ && explicit_ == null) {
            return new ValidationInfo("Detecting HuxerUI SDKs…", choices_);
        }
        if (Selection() != null) {
            return null;
        }
        String message = discovered_.isEmpty() && detection_problem_ != null ? detection_problem_
                : "Select or download a HuxerUI SDK to continue.";
        return new ValidationInfo(message, choices_);
    }

    void Discover(String project_location) {
        Path parent;
        try {
            parent = project_location.isBlank() ? null : Path.of(project_location).toAbsolutePath().normalize().getParent();
        } catch (InvalidPathException ignored) {
            return;
        }
        if (disposed_ || (search_started_ && Objects.equals(search_parent_, parent))) {
            return;
        }
        Search(parent);
    }

    private void Search(@Nullable Path parent) {
        search_started_ = true;
        search_parent_ = parent;
        int revision = ++search_revision_;
        searching_ = true;
        choices_.repaint();
        var application = ApplicationManager.getApplication();
        var modality = ModalityState.stateForComponent(this);
        application.executeOnPooledThread(() -> {
            Discovery result = FindSdks(parent);
            application.invokeLater(() -> {
                if (!disposed_ && revision == search_revision_) {
                    SetDiscovered(result.sdks(), result.problem());
                }
            }, modality);
        });
    }

    void Dispose() {
        disposed_ = true;
    }

    private static Discovery FindSdks(@Nullable Path parent) {
        Map<Path, HuxerUISdk> valid = new LinkedHashMap<>();
        String problem = null;
        for (Path directory : HuxerUISettings.getInstance().SdkCandidates(parent)) {
            try {
                Path home = directory.toRealPath();
                if (!valid.containsKey(home)) {
                    var sdk = HuxerUISdk.Inspect(home, null);
                    valid.put(home, sdk);
                }
            } catch (HuxerUISdk.UnsupportedVersionException error) {
                if (problem == null) {
                    problem = error.getMessage();
                }
            } catch (ProcessCanceledException error) {
                throw error;
            } catch (Exception ignored) {
                // A candidate is selectable only when its CLI passes the version check.
            }
        }
        return new Discovery(List.copyOf(valid.values()), problem);
    }

    void SetDiscovered(List<HuxerUISdk> candidates, @Nullable String problem) {
        searching_ = false;
        discovered_ = List.copyOf(candidates);
        detection_problem_ = problem;
        UpdateChoices();
    }

    private record Discovery(List<HuxerUISdk> sdks, @Nullable String problem) {}

    private void UpdateChoices() {
        Map<Path, HuxerUISdk> options = new LinkedHashMap<>();
        for (HuxerUISdk sdk : discovered_) {
            options.put(sdk.home(), sdk);
        }
        if (explicit_ != null) {
            options.put(explicit_.home(), explicit_);
        }
        HuxerUISdk selected = explicit_ != null ? explicit_ : options.size() == 1 ? options.values().iterator().next() : null;
        var model = new DefaultComboBoxModel<HuxerUISdk>();
        model.addElement(null);
        options.values().forEach(model::addElement);
        model.setSelectedItem(selected);
        updating_ = true;
        try {
            choices_.setModel(model);
        } finally {
            updating_ = false;
        }
        SelectionChanged();
    }

    private void SelectionChanged() {
        HuxerUISdk selected = Selection();
        choices_.setToolTipText(selected == null ? null : selected.home().toString());
        changed_.run();
    }

    private void ShowAddMenu() {
        JBPopupMenu menu = new JBPopupMenu();
        AddMenuItem(menu, "Download Latest HuxerUI SDK…", () ->
                SelectWithProgress("Install HuxerUI SDK", indicator ->
                        HuxerUISdk.Inspect(new SdkInstaller().InstallLatest(indicator), indicator)));
        AddMenuItem(menu, "Browse for Installed SDK…", () -> Browse(false));
        AddMenuItem(menu, "Use Source Checkout…", () -> Browse(true));
        menu.addSeparator();
        AddMenuItem(menu, "Refresh SDK List", () -> {
            Search(search_parent_);
            SelectionChanged();
        });
        JBPopupMenu.showBelow(add_, menu);
    }

    private static void AddMenuItem(JBPopupMenu menu, String name, Runnable action) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    private void Browse(boolean source) {
        var descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(source ? "Select HuxerUI Source Checkout" : "Select HuxerUI SDK");
        VirtualFile selected = FileChooser.chooseFile(descriptor, this, null, null);
        if (selected == null) {
            return;
        }
        Path home = selected.toNioPath();
        if (source && !SdkLayout.IsSource(home)) {
            Messages.showErrorDialog(this, "Select the root of a HuxerUI source checkout.", "Invalid HuxerUI Checkout");
        } else if (source) {
            SelectWithProgress("Prepare HuxerUI Source Checkout", indicator -> {
                try {
                    return HuxerUISdk.Inspect(home, indicator);
                } catch (ProcessCanceledException error) {
                    throw error;
                } catch (Exception error) {
                    return SourceCliBuild.BuildForWizard(home, indicator);
                }
            });
        } else {
            SelectWithProgress("Check HuxerUI SDK", indicator -> HuxerUISdk.Inspect(home, indicator));
        }
    }

    private void SelectWithProgress(String title, SdkOperation operation) {
        try {
            var progress = ProgressManager.getInstance();
            HuxerUISdk sdk = progress.runProcessWithProgressSynchronously(
                    () -> operation.Run(progress.getProgressIndicator()), title, true, null);
            if (!disposed_ && sdk != null) {
                explicit_ = sdk;
                UpdateChoices();
            }
        } catch (ProcessCanceledException ignored) {
            // The current SDK selection stays available when an operation is canceled.
        } catch (Exception error) {
            log.info(title + " failed", error);
            Messages.showErrorDialog(this, error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage(), title);
        }
    }

    @FunctionalInterface
    private interface SdkOperation {
        HuxerUISdk Run(ProgressIndicator indicator) throws Exception;
    }
}
