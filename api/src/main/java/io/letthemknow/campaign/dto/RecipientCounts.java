package io.letthemknow.campaign.dto;

public record RecipientCounts(long pending, long sending, long sent, long failed, long cancelled) {

    public long total() {
        return pending + sending + sent + failed + cancelled;
    }

    public long inFlight() {
        return pending + sending;
    }
}
