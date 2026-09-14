package io.letthemknow.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.letthemknow.integration.TransactionalStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** Synchronous result of a single push; {@code messageId} addresses the stored transactional message. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = """
        The final outcome of the send. A delivery failure is reported here with status FAILED and an
        error code, while the HTTP status stays 200 — the message was accepted and recorded either way.
        Only a bad request, a bad key or the rate limit produce a non-2xx status.""")
public record SinglePushResponse(
        @Schema(description = "Use it with GET /integration/messages/{id}.", example = "4821")
        Long messageId,

        @Schema(description = "SENT or FAILED. Always populated.", example = "SENT")
        TransactionalStatus status,

        @Schema(description = "Provider's id: the SMTP Message-ID, or the LINE message id.",
                example = "<a1b2c3@smtp.example.com>")
        String externalMessageId,

        @Schema(description = "Set only when status is FAILED.", example = "SMTP_INVALID_ADDRESS")
        String errorCode,

        @Schema(description = "Set only when status is FAILED.", example = "550 5.1.1 user unknown")
        String errorMessage,

        @Schema(description = "UTC, set only when status is SENT.", example = "2026-09-11T04:20:31Z")
        OffsetDateTime sentAt) {
}
