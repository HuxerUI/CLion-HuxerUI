package org.huxerui.clion.run;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.huxerui.clion.project.HuxerUIProjectService;
import org.huxerui.clion.cli.HuxerUIBuildOptions;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JComponent;

final class HuxerUIRunConfigurationEditor extends SettingsEditor<HuxerUIRunConfiguration> {
    private final Project project_;
    private final JComboBox<HuxerUIDevice> device_ = new JComboBox<>();
    private final JComboBox<String> profile_ = new JComboBox<>(new String[]{"debug", "release"});
    private final JBCheckBox watch_ = new JBCheckBox("Rebuild and relaunch on source changes");
    private final JBTextField abis_ = new JBTextField();
    private final JComboBox<String> optimization_ = new JComboBox<>(new String[]{"", "default", "size", "minsize"});
    private final JComboBox<String> lto_ = new JComboBox<>(new String[]{"", "on", "off"});

    HuxerUIRunConfigurationEditor(Project project) {
        project_ = project;
        abis_.getEmptyText().setText("SDK default");
        device_.addActionListener(event -> {
            boolean cmake = !(device_.getSelectedItem() instanceof HuxerUIDevice selected)
                    || !selected.platform().equals("mcpp");
            watch_.setEnabled(cmake);
            abis_.setEnabled(cmake);
            optimization_.setEnabled(cmake);
            lto_.setEnabled(cmake);
            if (cmake && device_.getSelectedItem() instanceof HuxerUIDevice selected) {
                var platforms = HuxerUIBuildOptions.Abis(selected.platform());
                abis_.setToolTipText(platforms.isEmpty() ? null : String.join(", ", platforms));
            }
        });
        device_.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focused
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof HuxerUIDevice device) {
                    setText(device.DisplayName());
                }
                return this;
            }
        });
    }

    @Override
    protected void resetEditorFrom(@NotNull HuxerUIRunConfiguration configuration) {
        device_.removeAllItems();
        HuxerUIProjectService service = HuxerUIProjectService.Get(project_);
        HuxerUIDevice selected = null;
        for (HuxerUIDevice device : service.Devices()) {
            if (device.IsReady()) {
                device_.addItem(device);
                if (device.platform().equals(configuration.GetPlatform())
                        && device.id().equals(configuration.GetDeviceId())) {
                    selected = device;
                }
            }
        }
        if (selected == null && !configuration.GetPlatform().isBlank()) {
            selected = new HuxerUIDevice(configuration.GetPlatform(), configuration.GetDeviceId(),
                    "Saved target", "");
            device_.addItem(selected);
        }
        if (selected != null) {
            device_.setSelectedItem(selected);
        }
        profile_.setSelectedItem(configuration.GetProfile());
        watch_.setSelected(configuration.IsWatch());
        var options = configuration.BuildOptions();
        abis_.setText(options.abis());
        optimization_.setSelectedItem(options.optimization());
        lto_.setSelectedItem(options.lto());
    }

    @Override
    protected void applyEditorTo(@NotNull HuxerUIRunConfiguration configuration) {
        HuxerUIDevice device = (HuxerUIDevice) device_.getSelectedItem();
        if (device != null) {
            configuration.SetPlatform(device.platform());
            configuration.SetDeviceId(device.id());
        }
        configuration.SetWatch(watch_.isEnabled() && watch_.isSelected());
        configuration.SetBuildOptions(new HuxerUIBuildOptions((String) profile_.getSelectedItem(),
                abis_.getText().strip(), (String) optimization_.getSelectedItem(), (String) lto_.getSelectedItem()));
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Device:"), device_)
                .addLabeledComponent(new JBLabel("Configuration:"), profile_)
                .addLabeledComponent(new JBLabel("ABIs:"), abis_)
                .addLabeledComponent(new JBLabel("Optimization:"), optimization_)
                .addLabeledComponent(new JBLabel("LTO:"), lto_)
                .addLabeledComponent(new JBLabel("Watch:"), watch_)
                .getPanel();
    }
}
