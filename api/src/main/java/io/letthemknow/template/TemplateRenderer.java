package io.letthemknow.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, safe Mustache-style substitution: {@code {{param}}} → value, missing params → empty string.
 * No sections, no expressions, no code execution. HTML output escapes parameter values.
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    public String render(String template, Map<String, String> params, boolean escapeHtml) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (m.find()) {
            String value = params == null ? null : params.get(m.group(1));
            String replacement = value == null ? "" : (escapeHtml ? escapeHtml(value) : value);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Deep-copies a JSON tree rendering every text value; keys are left untouched. */
    public JsonNode renderJson(JsonNode node, Map<String, String> params) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(render(node.textValue(), params, false));
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), renderJson(e.getValue(), params)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> copy.add(renderJson(child, params)));
            return copy;
        }
        return node;
    }

    public Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            Matcher m = PLACEHOLDER.matcher(template);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    public Set<String> placeholders(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collect(node, names);
        return names;
    }

    private void collect(JsonNode node, Set<String> into) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            into.addAll(placeholders(node.textValue()));
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, into));
        }
    }

    static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
