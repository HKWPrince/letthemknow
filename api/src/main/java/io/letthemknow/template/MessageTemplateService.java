package io.letthemknow.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.PageResponse;
import io.letthemknow.template.dto.MessageTemplateDto;
import io.letthemknow.template.dto.MessageTemplateRequest;
import io.letthemknow.template.dto.TemplatePreviewDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MessageTemplateService {

    public static final int LINE_MAX_MESSAGES = 5;

    private final MessageTemplateRepository repository;
    private final MessageTemplateMapper mapper;
    private final TemplateRenderer renderer;
    private final ObjectMapper objectMapper;

    public MessageTemplateService(MessageTemplateRepository repository, MessageTemplateMapper mapper,
                                  TemplateRenderer renderer, ObjectMapper objectMapper) {
        this.repository = repository;
        this.mapper = mapper;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MessageTemplateDto create(MessageTemplateRequest request) {
        String name = request.name().trim();
        if (repository.existsByName(name)) {
            throw new ApiException(ErrorCode.CONFLICT, "A template named '" + name + "' already exists");
        }
        validate(request);
        MessageTemplate template = new MessageTemplate(name, request.channelType(),
                templateTypeFor(request.channelType()), subjectOf(request), request.contentPayload().toString());
        return mapper.toDto(repository.save(template));
    }

    @Transactional
    public MessageTemplateDto update(Long id, MessageTemplateRequest request) {
        MessageTemplate template = require(id);
        String name = request.name().trim();
        if (!template.getName().equals(name) && repository.existsByName(name)) {
            throw new ApiException(ErrorCode.CONFLICT, "A template named '" + name + "' already exists");
        }
        validate(request);
        template.setName(name);
        template.setChannelType(request.channelType());
        template.setTemplateType(templateTypeFor(request.channelType()));
        template.setSubjectTemplate(subjectOf(request));
        template.setContentPayload(request.contentPayload().toString());
        return mapper.toDto(repository.save(template));
    }

    @Transactional(readOnly = true)
    public MessageTemplateDto get(Long id) {
        return mapper.toDto(require(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageTemplateDto> list(int page, int size, ChannelType channelType) {
        Pageable pageable = PageRequest.of(page, size);
        var result = channelType == null
                ? repository.findAllByOrderByUpdatedAtDesc(pageable)
                : repository.findAllByChannelTypeOrderByUpdatedAtDesc(channelType, pageable);
        return PageResponse.from(result, mapper::toDto);
    }

    @Transactional
    public void delete(Long id) {
        MessageTemplate template = require(id);
        try {
            repository.delete(template);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CONFLICT, "Template is referenced by campaigns or messages and cannot be deleted");
        }
    }

    @Transactional(readOnly = true)
    public TemplatePreviewDto preview(Long id, Map<String, String> params) {
        MessageTemplate template = require(id);
        JsonNode payload = parse(template.getContentPayload());
        Set<String> placeholders = new LinkedHashSet<>(renderer.placeholders(template.getSubjectTemplate()));
        placeholders.addAll(renderer.placeholders(payload));
        RenderedMessage rendered = render(template, params);
        return switch (rendered) {
            case RenderedMessage.Email e -> new TemplatePreviewDto(template.getChannelType(), placeholders,
                    e.subject(), e.html(), e.text(), null);
            case RenderedMessage.Line l -> new TemplatePreviewDto(template.getChannelType(), placeholders,
                    null, null, null, l.messages());
        };
    }

    /** Renders a template for one recipient. Email HTML escapes parameter values; LINE JSON does not. */
    public RenderedMessage render(MessageTemplate template, Map<String, String> params) {
        JsonNode payload = parse(template.getContentPayload());
        if (template.getChannelType() == ChannelType.EMAIL) {
            String subject = renderer.render(template.getSubjectTemplate(), params, false);
            String html = renderer.render(payload.path("html").asText(""), params, true);
            String text = payload.hasNonNull("text") ? renderer.render(payload.get("text").asText(), params, false) : null;
            return new RenderedMessage.Email(subject, html, text);
        }
        List<JsonNode> messages = new ArrayList<>();
        payload.path("messages").forEach(m -> messages.add(renderer.renderJson(m, params)));
        return new RenderedMessage.Line(messages);
    }

    public MessageTemplate require(Long id) {
        return repository.findScopedById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Template not found"));
    }

    /** Shape rules from CLAUDE.md §11. */
    static void validatePayload(ChannelType channelType, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "contentPayload must be a JSON object");
        }
        if (channelType == ChannelType.EMAIL) {
            JsonNode html = payload.get("html");
            if (html == null || !html.isTextual() || html.asText().isBlank()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "EMAIL payload requires a non-empty \"html\" string");
            }
            JsonNode text = payload.get("text");
            if (text != null && !text.isNull() && !text.isTextual()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "EMAIL payload \"text\" must be a string");
            }
            return;
        }
        JsonNode messages = payload.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty() || messages.size() > LINE_MAX_MESSAGES) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "LINE payload requires \"messages\": an array of 1 to " + LINE_MAX_MESSAGES + " message objects");
        }
        for (JsonNode m : messages) {
            if (!m.isObject() || !m.path("type").isTextual() || m.path("type").asText().isBlank()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Each LINE message must be an object with a \"type\"");
            }
        }
    }

    private static void validate(MessageTemplateRequest request) {
        validatePayload(request.channelType(), request.contentPayload());
        if (request.channelType() == ChannelType.EMAIL
                && (request.subjectTemplate() == null || request.subjectTemplate().isBlank())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "subjectTemplate is required for EMAIL templates");
        }
    }

    private static String subjectOf(MessageTemplateRequest request) {
        return request.channelType() == ChannelType.EMAIL ? request.subjectTemplate().trim() : null;
    }

    static TemplateType templateTypeFor(ChannelType channelType) {
        return channelType == ChannelType.EMAIL ? TemplateType.EMAIL_HTML : TemplateType.LINE_MESSAGES;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Stored template payload is not valid JSON", e);
        }
    }
}
