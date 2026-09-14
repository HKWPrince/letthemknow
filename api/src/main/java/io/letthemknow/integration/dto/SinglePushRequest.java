package io.letthemknow.integration.dto;

import io.letthemknow.channel.ChannelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Exactly one of {@code templateId} / {@code templateName} must be given. */
@Schema(description = "One message to one recipient, rendered from a template you created in the console.")
public record SinglePushRequest(
        @NotNull
        @Schema(description = "Must match the template's channel.", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
        ChannelType channel,

        @Schema(description = "Template id. Provide this or templateName, not both.", example = "12")
        Long templateId,

        @Size(max = 100)
        @Schema(description = "Template name as shown in the console. Usually easier to keep stable than an id.",
                example = "order-shipped")
        String templateName,

        @NotBlank @Size(max = 255)
        @Schema(description = "Email address, or a LINE user id matching ^U[0-9a-f]{32}$.",
                example = "ann.chen@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        String recipient,

        @Schema(description = "Values for the template's {{placeholders}}. Missing ones render as empty.",
                example = "{\"name\":\"Ann Chen\",\"order\":\"SO-10482\"}")
        Map<String, String> params) {

    public Map<String, String> paramsOrEmpty() {
        return params == null ? Map.of() : params;
    }
}
