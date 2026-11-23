package org.huxerui.clion.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HuxerUIPreviewSizeTest {
    @Test
    void parsesCustomLogicalDimensions() {
        assertEquals(new HuxerUIPreviewSize(513, 623), HuxerUIPreviewSize.Parse(" 513 ", "623"));
        assertEquals(new HuxerUIPreviewSize(1, 4096), HuxerUIPreviewSize.Parse("1", "4096"));
    }

    @Test
    void rejectsMissingInvalidAndOversizedDimensions() {
        assertNull(HuxerUIPreviewSize.Parse("", "844"));
        assertNull(HuxerUIPreviewSize.Parse("390", "0"));
        assertNull(HuxerUIPreviewSize.Parse("390.5", "844"));
        assertNull(HuxerUIPreviewSize.Parse("200", "4097"));
        assertNull(HuxerUIPreviewSize.Parse("9999999999", "844"));
    }
}
