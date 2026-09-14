package io.letthemknow.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void substitutesPlaceholdersAndBlanksMissingOnes() {
        String out = renderer.render("Hi {{name}}, code {{ code }} / {{missing}}!", Map.of("name", "Ann", "code", "X1"), false);

        assertThat(out).isEqualTo("Hi Ann, code X1 / !");
    }

    @Test
    void escapesHtmlOnlyWhenAsked() {
        Map<String, String> params = Map.of("name", "<b>Ann & \"Co\"</b>");

        assertThat(renderer.render("<p>{{name}}</p>", params, true)).isEqualTo("<p>&lt;b&gt;Ann &amp; &quot;Co&quot;&lt;/b&gt;</p>");
        assertThat(renderer.render("{{name}}", params, false)).isEqualTo("<b>Ann & \"Co\"</b>");
    }

    @Test
    void neverExecutesAnythingAndLeavesUnknownSyntaxAlone() {
        String out = renderer.render("{{#if}}{{name}}{{/if}} {{ }} {{a b}}", Map.of("name", "x"), false);

        assertThat(out).isEqualTo("{{#if}}x{{/if}} {{ }} {{a b}}");
    }

    @Test
    void rendersJsonTreesDeeply() throws Exception {
        JsonNode tree = json.readTree("""
                {"type":"flex","altText":"Hello {{name}}","contents":{"body":{"contents":[{"type":"text","text":"Order {{order}}"}]},"n":3,"ok":true}}
                """);

        JsonNode out = renderer.renderJson(tree, Map.of("name", "Ann", "order", "42"));

        assertThat(out.get("altText").asText()).isEqualTo("Hello Ann");
        assertThat(out.at("/contents/body/contents/0/text").asText()).isEqualTo("Order 42");
        assertThat(out.at("/contents/n").asInt()).isEqualTo(3);
        assertThat(out.at("/contents/ok").asBoolean()).isTrue();
        assertThat(tree.get("altText").asText()).as("source untouched").isEqualTo("Hello {{name}}");
        assertThat(renderer.placeholders(tree)).containsExactly("name", "order");
    }

    @Test
    void listsPlaceholdersInOrder() {
        assertThat(renderer.placeholders("{{b}} {{a}} {{b}} {{c.d-e_f}}")).containsExactly("b", "a", "c.d-e_f");
        assertThat(renderer.placeholders((String) null)).isEmpty();
    }
}
