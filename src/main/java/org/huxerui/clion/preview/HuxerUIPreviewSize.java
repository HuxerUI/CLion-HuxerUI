package org.huxerui.clion.preview;

import org.jetbrains.annotations.Nullable;

record HuxerUIPreviewSize(int width, int height) {
    static final HuxerUIPreviewSize phone = new HuxerUIPreviewSize(390, 844);
    static final int max_dimension = 4096;

    static @Nullable HuxerUIPreviewSize Parse(String width, String height) {
        try {
            int w = Integer.parseInt(width.strip());
            int h = Integer.parseInt(height.strip());
            return w >= 1 && w <= max_dimension && h >= 1 && h <= max_dimension
                    ? new HuxerUIPreviewSize(w, h) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public String toString() {
        return width + " × " + height;
    }
}
