package io.letthemknow.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Secret and token may be omitted on update to keep the stored values. */
public record LineChannelRequest(
        @NotBlank @Size(max = 100) String channelId,
        @Size(max = 4000) String channelSecret,
        @Size(max = 4000) String channelAccessToken) {
}
