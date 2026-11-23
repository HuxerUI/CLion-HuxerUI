package org.huxerui.clion.preview;

import org.jetbrains.annotations.Nullable;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterCpp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class HuxerUIPreviewCpp {
    private static final Set<String> containers = Set.of("translation_unit", "declaration_list", "field_declaration_list",
            "template_declaration", "linkage_specification", "preproc_if", "preproc_ifdef", "preproc_elif",
            "preproc_elifdef", "preproc_else");
    private static final Set<String> extensions = Set.of("cpp", "cc", "cxx", "c++", "h", "hh", "hpp", "hxx", "inl");
    private static final Set<String> source_extensions = Set.of("cpp", "cc", "cxx", "c++");
    private static final Pattern qualified_name = Pattern.compile("(?U)(?:::)?[\\p{L}_][\\p{L}\\p{N}_]*"
            + "(?:::[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final Pattern integer_alias = Pattern.compile("(?:::)?(?:std::)?(?:u?int(?:8|16|32|64)_t|size_t|ptrdiff_t)");

    record Function(String name, int offset, int start, int end, String signature, List<String> arguments,
            boolean needs_sample, boolean returns_view) {
        String Call() {
            return name + "(" + String.join(", ", arguments) + ")";
        }

        @Nullable String PreviewCall(@Nullable String custom_call) {
            if (custom_call != null) {
                return custom_call.isEmpty() ? null : custom_call;
            }
            return needs_sample ? null : Call();
        }
    }

    private record Range(int start, int end) {}

    private static final class Grammar {
        private static final TreeSitterCpp cpp = new TreeSitterCpp();
    }

    private final String text_;
    private final List<Function> functions_ = new ArrayList<>();
    private final List<Range> registrations_ = new ArrayList<>();

    private HuxerUIPreviewCpp(String text) {
        text_ = text;
    }

    static boolean Supports(String extension) {
        return extension != null && extensions.contains(extension.toLowerCase(java.util.Locale.ROOT));
    }

    static boolean IsSource(String extension) {
        return extension != null && source_extensions.contains(extension.toLowerCase(java.util.Locale.ROOT));
    }

    static HuxerUIPreviewCpp Parse(String text) {
        byte[] source = text.getBytes(StandardCharsets.UTF_16LE);
        var result = new HuxerUIPreviewCpp(text);
        try (TSParser parser = new TSParser()) {
            if (!parser.setLanguage(Grammar.cpp)) {
                throw new IllegalStateException("Could not initialize the C++ preview parser.");
            }
            try (var tree = parser.parse(new byte[4096], null, (buffer, offset, point) -> {
                int count = Math.min(buffer.length, source.length - offset);
                if (count > 0) {
                    System.arraycopy(source, offset, buffer, 0, count);
                }
                return Math.max(0, count);
            }, TSInputEncoding.TSInputEncodingUTF16LE)) {
                result.Visit(tree.getRootNode(), "::", false, false);
            }
        }
        return result;
    }

    List<Function> Functions() {
        return functions_;
    }

    @Nullable Function FindFunction(String name, int offset) {
        return functions_.stream().filter(function -> function.offset() == offset && function.name().equals(name))
                .findFirst().orElse(null);
    }

    boolean HasRegistration() {
        return !registrations_.isEmpty();
    }

    String WithoutRegistrations() {
        if (!HasRegistration()) {
            return text_;
        }
        var output = new StringBuilder(text_);
        for (Range range : registrations_) {
            for (int offset = range.start(); offset < range.end(); ++offset) {
                if (output.charAt(offset) != '\n' && output.charAt(offset) != '\r') {
                    output.setCharAt(offset, ' ');
                }
            }
        }
        return output.toString();
    }

    private void Visit(TSNode node, String scope, boolean member, boolean application_in_scope) {
        switch (node.getType()) {
            case "namespace_definition", "class_specifier", "struct_specifier" -> {
                boolean class_scope = !node.getType().equals("namespace_definition");
                TSNode body = node.getChildByFieldName("body");
                if (!body.isNull()) {
                    TSNode name = node.getChildByFieldName("name");
                    String prefix = name.isNull() ? scope : scope + Spelling(name) + "::";
                    Visit(body, prefix, member || class_scope, application_in_scope);
                }
            }
            case "function_definition" -> VisitFunction(node, scope, member);
            case "declaration" -> {
                TSNode type = node.getChildByFieldName("type");
                if (!member && ApplicationType(type, application_in_scope) && HasInitializer(node, scope)) {
                    AddRegistration(node);
                }
            }
            default -> {
                if (containers.contains(node.getType())) {
                    boolean application_visible = application_in_scope;
                    for (int index = 0; index < node.getNamedChildCount(); ++index) {
                        TSNode child = node.getNamedChild(index);
                        if (child.getType().equals("using_declaration") || child.getType().equals("alias_declaration")) {
                            application_visible |= ImportsApplication(child);
                        }
                        Visit(child, scope, member, application_visible);
                    }
                }
            }
        }
    }

    private void VisitFunction(TSNode node, String scope, boolean member) {
        TSNode declarator = node.getChildByFieldName("declarator");
        if (declarator.isNull() || !declarator.getType().equals("function_declarator")) {
            return;
        }
        TSNode name = declarator.getChildByFieldName("declarator");
        if (name.isNull()) {
            return;
        }
        String spelling = Spelling(name);
        if (scope.equals("::") && spelling.equals("main")) {
            AddRegistration(node);
            return;
        }
        TSNode result = node.getChildByFieldName("type");
        for (int index = 0; index < declarator.getNamedChildCount(); ++index) {
            TSNode child = declarator.getNamedChild(index);
            if (child.getType().equals("trailing_return_type")) {
                TSNode type = child.getNamedChild(0);
                if (!type.getChildByFieldName("declarator").isNull()) {
                    return;
                }
                result = type.getChildByFieldName("type");
            }
        }
        if (result.isNull() || result.getType().equals("primitive_type") || !qualified_name.matcher(spelling).matches()) {
            return;
        }

        TSNode parameters = declarator.getChildByFieldName("parameters");
        String signature = Text(parameters).replaceAll("\\s+", " ");
        TSNode identifier = name;
        while (identifier.getType().equals("qualified_identifier")) {
            identifier = identifier.getChildByFieldName("name");
        }
        var arguments = new ArrayList<String>();
        boolean needs_sample = member && !HasSpecifier(node, "static");
        for (int index = 0; index < parameters.getNamedChildCount(); ++index) {
            TSNode parameter = parameters.getNamedChild(index);
            if (!parameter.getType().equals("parameter_declaration") || Text(parameter).strip().equals("void")) {
                continue;
            }
            String sample = SampleValue(parameter);
            if (sample == null) {
                sample = Placeholder(parameter);
                needs_sample = true;
            }
            arguments.add(sample);
        }
        functions_.add(new Function(spelling.startsWith("::") ? spelling : scope + spelling, Start(identifier),
                Start(node), End(node), signature, List.copyOf(arguments), needs_sample, TypeIs(result, "View")));
    }

    private @Nullable String SampleValue(TSNode parameter) {
        TSNode type_node = parameter.getChildByFieldName("type");
        if (type_node.isNull()) {
            return null;
        }
        String type = Text(type_node).strip().replaceAll("\\s+", " ");
        TSNode declarator = parameter.getChildByFieldName("declarator");
        if (!declarator.isNull() && !declarator.getType().equals("identifier")) {
            if (type.equals("char") && declarator.getType().equals("pointer_declarator")
                    && IsConst(parameter) && declarator.getChildByFieldName("declarator").getType().equals("identifier")) {
                return "\"Sample\"";
            }
            boolean reference = declarator.getType().equals("reference_declarator")
                    || declarator.getType().equals("abstract_reference_declarator");
            if (!reference || (!IsConst(parameter) && !Text(declarator).startsWith("&&"))) {
                return null;
            }
        }
        return switch (type) {
            case "bool" -> "false";
            case "int" -> "1";
            case "char" -> "'A'";
            case "wchar_t" -> "L'A'";
            case "char8_t" -> "u8'A'";
            case "char16_t" -> "u'A'";
            case "char32_t" -> "U'A'";
            case "float" -> "1.0F";
            case "double" -> "1.0";
            case "long double" -> "1.0L";
            case "std::string", "::std::string", "std::string_view", "::std::string_view" -> type + "{\"Sample\"}";
            default -> {
                if (type_node.getType().equals("sized_type_specifier") || integer_alias.matcher(type).matches()) {
                    yield "static_cast<" + type + ">(1)";
                }
                String spelling = type.startsWith("::") ? type.substring(2) : type;
                yield spelling.startsWith("std::optional<") ? type + "{}" : null;
            }
        };
    }

    private boolean IsConst(TSNode node) {
        for (int index = 0; index < node.getNamedChildCount(); ++index) {
            TSNode child = node.getNamedChild(index);
            if (child.getType().equals("type_qualifier") && Text(child).equals("const")) {
                return true;
            }
        }
        return false;
    }

    private String Placeholder(TSNode parameter) {
        TSNode declarator = parameter.getChildByFieldName("declarator");
        while (!declarator.isNull() && !declarator.getType().equals("identifier")) {
            declarator = declarator.getChildByFieldName("declarator");
        }
        return "/* " + (declarator.isNull() ? "argument" : Text(declarator)) + " */";
    }

    private boolean HasInitializer(TSNode node, String scope) {
        if (HasSpecifier(node, "extern")) {
            return false;
        }
        for (int index = 0; index < node.getChildCount(); ++index) {
            if ("declarator".equals(node.getFieldNameForChild(index))) {
                TSNode candidate = node.getChild(index);
                if (candidate.getType().equals("init_declarator")) {
                    return true;
                }
                if (candidate.getType().equals("function_declarator")) {
                    TSNode parameters = candidate.getChildByFieldName("parameters");
                    if (parameters.getNamedChildCount() != 1) {
                        continue;
                    }
                    String argument = Spelling(parameters.getNamedChild(0));
                    if (functions_.stream().anyMatch(function ->
                            function.name().equals(argument) || function.name().equals(scope + argument))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean ApplicationType(TSNode type, boolean application_in_scope) {
        return TypeIs(type, "Application") && (application_in_scope || !Spelling(type).equals("Application"));
    }

    private boolean ImportsApplication(TSNode node) {
        return switch (Spelling(node)) {
            case "usingnamespacehuxerui;", "usingnamespace::huxerui;", "usinghuxerui::Application;",
                    "using::huxerui::Application;", "usingApplication=huxerui::Application;",
                    "usingApplication=::huxerui::Application;" -> true;
            default -> false;
        };
    }

    private boolean HasSpecifier(TSNode node, String specifier) {
        for (int index = 0; index < node.getNamedChildCount(); ++index) {
            TSNode child = node.getNamedChild(index);
            if (child.getType().equals("storage_class_specifier") && Text(child).equals(specifier)) {
                return true;
            }
        }
        return false;
    }

    private boolean TypeIs(TSNode node, String type) {
        if (node.isNull()) {
            return false;
        }
        String spelling = Spelling(node);
        return spelling.equals(type) || spelling.equals("huxerui::" + type) || spelling.equals("::huxerui::" + type);
    }

    private String Spelling(TSNode node) {
        return Text(node).replaceAll("\\s+", "");
    }

    private String Text(TSNode node) {
        return text_.substring(Start(node), End(node));
    }

    private void AddRegistration(TSNode node) {
        registrations_.add(new Range(Start(node), End(node)));
    }

    private static int Start(TSNode node) {
        return node.getStartByte() / 2;
    }

    private static int End(TSNode node) {
        return node.getEndByte() / 2;
    }
}
