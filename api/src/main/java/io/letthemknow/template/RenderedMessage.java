package io.letthemknow.template;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Output of rendering a template for one recipient. */
public sealed interface RenderedMessage {

    record Email(String subject, String html, String text) implements RenderedMessage {}

    record Line(List<JsonNode> messages) implements RenderedMessage {}
}
