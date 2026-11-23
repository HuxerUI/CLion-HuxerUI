package org.huxerui.clion.preview;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

final class HuxerUIPreviewIcons {
    static final Icon gutter = IconLoader.getIcon("/icons/previewGutter.svg", HuxerUIPreviewIcons.class);
    static final Icon view = IconLoader.getIcon("/icons/preview.svg", HuxerUIPreviewIcons.class);
    static final Icon arguments = IconLoader.getIcon("/icons/previewArguments.svg", HuxerUIPreviewIcons.class);
    static final Icon refresh = IconLoader.getIcon("/icons/previewRefresh.svg", HuxerUIPreviewIcons.class);

    private HuxerUIPreviewIcons() {}
}
