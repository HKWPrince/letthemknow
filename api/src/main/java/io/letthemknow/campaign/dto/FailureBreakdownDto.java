package io.letthemknow.campaign.dto;

/** How many recipients failed with one error code; drives the console's resolution filter. */
public record FailureBreakdownDto(String errorCode, long count) {
}
