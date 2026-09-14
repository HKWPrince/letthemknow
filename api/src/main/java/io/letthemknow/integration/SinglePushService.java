package io.letthemknow.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.channel.email.EmailSender;
import io.letthemknow.channel.line.LineMessages;
import io.letthemknow.channel.line.LineSender;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.dispatch.DispatchError;
import io.letthemknow.dispatch.DispatchErrorClassifier;
import io.letthemknow.integration.dto.SinglePushRequest;
import io.letthemknow.integration.dto.SinglePushResponse;
import io.letthemknow.integration.dto.TransactionalMessageDto;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateRepository;
import io.letthemknow.template.MessageTemplateService;
import io.letthemknow.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * ERP-facing single message: persists a {@code transactional_messages} row, sends synchronously and
 * records the outcome. No campaign row, no retries — the caller decides whether to send again.
 */
@Service
public class SinglePushService {

    private static final Logger log = LoggerFactory.getLogger(SinglePushService.class);

    private final TransactionalMessageRepository messages;
    private final MessageTemplateRepository templates;
    private final MessageTemplateService templateService;
    private final EmailSender emailSender;
    private final LineSender lineSender;
    private final DispatchErrorClassifier classifier;
    private final TransactionalMessageMapper mapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SinglePushService(TransactionalMessageRepository messages, MessageTemplateRepository templates,
                             MessageTemplateService templateService, EmailSender emailSender, LineSender lineSender,
                             DispatchErrorClassifier classifier, TransactionalMessageMapper mapper,
                             ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.messages = messages;
        this.templates = templates;
        this.templateService = templateService;
        this.emailSender = emailSender;
        this.lineSender = lineSender;
        this.classifier = classifier;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    public SinglePushResponse push(SinglePushRequest request) {
        long tenantId = TenantContextHolder.require();
        MessageTemplate template = resolveTemplate(request);

        TransactionalMessage message = tx.execute(s -> messages.save(new TransactionalMessage(
                request.channel(), template.getId(), request.recipient().trim(), paramsJson(request))));

        try {
            String externalId = send(tenantId, request, template);
            TransactionalMessage sent = tx.execute(s -> {
                TransactionalMessage row = messages.findScopedById(message.getId()).orElseThrow();
                row.setStatus(TransactionalStatus.SENT);
                row.setExternalMessageId(externalId);
                row.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
                return messages.save(row);
            });
            return response(sent);
        } catch (Exception e) {
            DispatchError error = classifier.classify(e);
            log.info("Single push {} failed for tenant {}: {} {}", message.getId(), tenantId, error.code(), error.message());
            TransactionalMessage failed = tx.execute(s -> {
                TransactionalMessage row = messages.findScopedById(message.getId()).orElseThrow();
                row.setStatus(TransactionalStatus.FAILED);
                row.setErrorCode(error.code());
                row.setErrorMessage(error.message());
                return messages.save(row);
            });
            return response(failed);
        }
    }

    @Transactional(readOnly = true)
    public TransactionalMessageDto get(Long id) {
        return messages.findScopedById(id)
                .map(mapper::toDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Message not found"));
    }

    private String send(long tenantId, SinglePushRequest request, MessageTemplate template) {
        if (request.channel() == ChannelType.EMAIL) {
            RenderedMessage.Email email = (RenderedMessage.Email) templateService.render(template, request.paramsOrEmpty());
            return emailSender.send(tenantId, request.recipient().trim(), email.subject(), email.html(), email.text());
        }
        RenderedMessage.Line line = (RenderedMessage.Line) templateService.render(template, request.paramsOrEmpty());
        LineMessages.SendResult result = lineSender.push(tenantId, request.recipient().trim(), line.messages(),
                UUID.randomUUID().toString());
        return result.messageIds().isEmpty() ? result.requestId() : result.messageIds().get(0);
    }

    private MessageTemplate resolveTemplate(SinglePushRequest request) {
        boolean byId = request.templateId() != null;
        boolean byName = request.templateName() != null && !request.templateName().isBlank();
        if (byId == byName) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Provide exactly one of templateId or templateName");
        }
        MessageTemplate template = tx.execute(s -> byId
                ? templates.findScopedById(request.templateId()).orElse(null)
                : templates.findByName(request.templateName().trim()).orElse(null));
        if (template == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Template not found");
        }
        if (template.getChannelType() != request.channel()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Template channel (" + template.getChannelType()
                    + ") does not match the requested channel (" + request.channel() + ")");
        }
        return template;
    }

    private String paramsJson(SinglePushRequest request) {
        if (request.paramsOrEmpty().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.paramsOrEmpty());
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "params could not be serialised", e);
        }
    }

    private static SinglePushResponse response(TransactionalMessage message) {
        return new SinglePushResponse(message.getId(), message.getStatus(), message.getExternalMessageId(),
                message.getErrorCode(), message.getErrorMessage(), message.getSentAt());
    }
}
