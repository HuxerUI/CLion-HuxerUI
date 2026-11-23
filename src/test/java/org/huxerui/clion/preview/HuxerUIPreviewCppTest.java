package org.huxerui.clion.preview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HuxerUIPreviewCppTest {
    @Test
    void findsViewDefinitionsAndTheirEditorOffsets() {
        String source = """
                // 测试 😀
                namespace widgets::demo {
                [[huxerui::composable]]
                static huxerui::View Counter(std::string_view title, int n) { return {}; }

                auto Footer(void) -> ::huxerui::View { return {}; }
                View WithDefault(int n = 42) { return {}; }
                View* Pointer() { return nullptr; }
                auto Reference() -> huxerui::View& { return value; }
                huxerui::View Declared();
                const char* Fake = "huxerui::View NotAFunction() {}";
                }
                namespace {
                View Hidden() { return {}; }
                }
                """;
        var functions = HuxerUIPreviewCpp.Parse(source).Functions().stream()
                .filter(HuxerUIPreviewCpp.Function::returns_view).toList();
        assertEquals(List.of("::widgets::demo::Counter", "::widgets::demo::Footer",
                "::widgets::demo::WithDefault", "::Hidden"), functions.stream().map(HuxerUIPreviewCpp.Function::name).toList());
        assertEquals(source.indexOf("Counter"), functions.getFirst().offset());
        assertEquals("::widgets::demo::Counter(std::string_view{\"Sample\"}, 1)", functions.getFirst().Call());
        assertTrue(functions.stream().noneMatch(HuxerUIPreviewCpp.Function::needs_sample));
    }

    @Test
    void generatesCommonArgumentsAndPreservesDeclaredDefaults() {
        String source = """
                View Card(const std::string& title, unsigned count, bool selected,
                        const char* subtitle, float opacity = 0.8F) { return {}; }
                View WithDefault(int value = 42) { return {}; }
                """;
        var functions = HuxerUIPreviewCpp.Parse(source).Functions();
        assertEquals("::Card(std::string{\"Sample\"}, static_cast<unsigned>(1), false, \"Sample\")",
                functions.getFirst().Call());
        assertFalse(functions.getFirst().needs_sample());
        assertEquals("::WithDefault()", functions.getLast().Call());
    }

    @Test
    void asksOnlyForArgumentsWithoutAnInferredSample() {
        String source = "huxerui::View Card(int size, Palette palette, Theme* theme) { return {}; }";
        var function = HuxerUIPreviewCpp.Parse(source).Functions().getFirst();
        assertEquals("::Card(1, /* palette */, /* theme */)", function.Call());
        assertTrue(function.needs_sample());
    }

    @Test
    void resolvesTheSelectedOverloadAfterASignatureChange() {
        String source = """
                View Card(bool enabled) { return {}; }
                View Card(std::string_view title) { return {}; }
                """;
        var syntax = HuxerUIPreviewCpp.Parse(source);
        var updated = syntax.FindFunction("::Card", source.lastIndexOf("Card"));
        assertEquals("::Card(std::string_view{\"Sample\"})", updated.PreviewCall(null));
        assertEquals("::Card(\"Edited\")", updated.PreviewCall("::Card(\"Edited\")"));
        assertNull(syntax.FindFunction("::Card", source.length()));
    }

    @Test
    void retainsAliasedAndDeducedFunctionsForTheContextAction() {
        String source = """
                Screen Alias() { return {}; }
                auto Deduced() { return BuildView(); }
                int Count() { return 3; }
                """;
        var functions = HuxerUIPreviewCpp.Parse(source).Functions();
        assertEquals(List.of("::Alias", "::Deduced"), functions.stream().map(HuxerUIPreviewCpp.Function::name).toList());
        assertTrue(functions.stream().noneMatch(HuxerUIPreviewCpp.Function::returns_view));
        assertTrue(functions.getFirst().end() > source.indexOf("return"));
    }

    @Test
    void distinguishesStaticAndInstanceMethods() {
        String source = """
                namespace demo {
                struct Panel {
                    static View StaticView() { return {}; }
                    View InstanceView() const { return {}; }
                };
                }
                """;
        var functions = HuxerUIPreviewCpp.Parse(source).Functions();
        assertEquals("::demo::Panel::StaticView()", functions.getFirst().Call());
        assertFalse(functions.getFirst().needs_sample());
        assertTrue(functions.getLast().needs_sample());
    }

    @Test
    void omitsApplicationRegistrationFromTheTemporarySource() {
        String source = """
                namespace vendor {
                    class Application {};
                    const Application instance{};
                }
                using namespace huxerui;
                View Root() { return {}; }
                const Application app{
                    Root,
                    {}
                };
                const huxerui::Application parenthesized(Root);
                huxerui::Application MakeApplication();
                extern const huxerui::Application external;
                int main() { return 0; }
                """;
        String staged = HuxerUIPreviewCpp.Parse(source).WithoutRegistrations();
        assertEquals(source.length(), staged.length());
        assertEquals(source.indexOf("View Root"), staged.indexOf("View Root"));
        assertTrue(staged.contains("const Application instance{};"));
        assertTrue(staged.contains("huxerui::Application MakeApplication();"));
        assertTrue(staged.contains("extern const huxerui::Application external;"));
        assertFalse(staged.contains("Application app{"));
        assertFalse(staged.contains("parenthesized"));
        assertFalse(staged.contains("int main()"));
    }
}
