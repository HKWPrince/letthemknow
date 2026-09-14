package io.letthemknow.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.campaign.CampaignRecipient;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.channel.email.EmailSender;
import io.letthemknow.channel.line.LineMessages;
import io.letthemknow.channel.line.LineSender;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateService;
import io.letthemknow.template.RenderedMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders and sends a claimed chunk. Email: one SMTP send per recipient. LINE: recipients are grouped by
 * rendered payload and each group goes out as one multicast (≤ 500), so unpersonalised campaigns cost
 * one API call per chunk.
 */
@Component
public class ChannelDispatcher {

    private final MessageTemplateService templates;
    private final EmailSender emailSender;
    private final LineSender lineSender;
    private final DispatchErrorClassifier classifier;
    private final ObjectMapper objectMapper;

    public ChannelDispatcher(MessageTemplateService templates, EmailSender emailSender, LineSender lineSender,
                             DispatchErrorClassifier classifier, ObjectMapper objectMapper) {
        this.templates = templates;
        this.emailSender = emailSender;
        this.lineSender = lineSender;
        this.classifier = classifier;
        this.objectMapper = objectMapper;
    }

    public Map<Long, SendOutcome> send(long tenantId, ChannelType channel, MessageTemplate template,
                                       List<CampaignRecipient> recipients) {
        return channel == ChannelType.EMAIL
                ? sendEmails(tenantId, template, recipients)
                : sendLine(tenantId, template, recipients);
    }

    private Map<Long, SendOutcome> sendEmails(long tenantId, MessageTemplate template, List<CampaignRecipient> recipients) {
        Map<Long, SendOutcome> outcomes = new HashMap<>();
        for (CampaignRecipient r : recipients) {
            try {
                RenderedMessage.Email email = (RenderedMessage.Email) templates.render(template, params(r));
                String id = emailSender.send(tenantId, r.getRecipientIdentifier(), email.subject(), email.html(), email.text());
                outcomes.put(r.getId(), new SendOutcome.Sent(id));
            } catch (Exception e) {
                outcomes.put(r.getId(), new SendOutcome.Failed(classifier.classify(e)));
            }
        }
        return outcomes;
    }

    private Map<Long, SendOutcome> sendLine(long tenantId, MessageTemplate template, List<CampaignRecipient> recipients) {
        Map<Long, SendOutcome> outcomes = new HashMap<>();
        Map<String, List<CampaignRecipient>> groups = new LinkedHashMap<>();
        Map<String, List<JsonNode>> renderedByKey = new HashMap<>();
        for (CampaignRecipient r : recipients) {
            try {
                RenderedMessage.Line line = (RenderedMessage.Line) templates.render(template, params(r));
                String key = objectMapper.writeValueAsString(line.messages());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                renderedByKey.putIfAbsent(key, line.messages());
            } catch (Exception e) {
                outcomes.put(r.getId(), new SendOutcome.Failed(classifier.classify(e)));
            }
        }
        groups.forEach((key, members) -> {
            List<String> userIds = members.stream().map(CampaignRecipient::getRecipientIdentifier).toList();
            try {
                LineMessages.SendResult result = lineSender.multicast(tenantId, userIds, renderedByKey.get(key),
                        UUID.randomUUID().toString());
                members.forEach(m -> outcomes.put(m.getId(), new SendOutcome.Sent(result.requestId())));
            } catch (Exception e) {
                DispatchError error = classifier.classify(e);
                members.forEach(m -> outcomes.put(m.getId(), new SendOutcome.Failed(error)));
            }
        });
        return outcomes;
    }

    private Map<String, String> params(CampaignRecipient recipient) {
        String json = recipient.getPayloadParams();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, String> params = new HashMap<>();
            raw.forEach((k, v) -> params.put(k, v == null ? "" : String.valueOf(v)));
            return params;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
