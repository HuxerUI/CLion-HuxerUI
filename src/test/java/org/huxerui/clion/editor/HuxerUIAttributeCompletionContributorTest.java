package org.huxerui.clion.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HuxerUIAttributeCompletionContributorTest {
    @Test
    void completesTheFullNameAtTheStartOfAnAttribute() {
        assertEquals(new HuxerUIAttributeCompletionContributor.AttributePrefix("hu", "huxerui::composable"),
                HuxerUIAttributeCompletionContributor.FindPrefix("[[hu]]", "[[hu".length()));
        assertEquals(new HuxerUIAttributeCompletionContributor.AttributePrefix("", "huxerui::composable"),
                HuxerUIAttributeCompletionContributor.FindPrefix("[[]]", 2));
    }

    @Test
    void completesOnlyTheNameAfterTheHuxerUINamespace() {
        String source = "[[huxerui::com";
        assertEquals(new HuxerUIAttributeCompletionContributor.AttributePrefix("com", "composable"),
                HuxerUIAttributeCompletionContributor.FindPrefix(source, source.length()));
    }

    @Test
    void ignoresNamesOutsideTheHuxerUIAttribute() {
        assertNull(HuxerUIAttributeCompletionContributor.FindPrefix("huxerui::com", "huxerui::com".length()));
        assertNull(HuxerUIAttributeCompletionContributor.FindPrefix("[[gnu::co", "[[gnu::co".length()));
        assertNull(HuxerUIAttributeCompletionContributor.FindPrefix("[[nodiscard", "[[nodiscard".length()));
        assertNull(HuxerUIAttributeCompletionContributor.FindPrefix("[[hu\n", "[[hu\n".length()));
    }
}
