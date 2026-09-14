package io.letthemknow.campaign;

import java.util.Set;

public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    PROCESSING,
    COMPLETED,
    AWAITING_RESOLUTION,
    RETRYING,
    TERMINATED;

    private static final Set<CampaignStatus> PROCESSING_STATES = Set.of(PROCESSING, RETRYING);
    private static final Set<CampaignStatus> FINAL_STATES = Set.of(COMPLETED, TERMINATED);

    /** {@code RETRYING} is a processing state. */
    public boolean isProcessing() {
        return PROCESSING_STATES.contains(this);
    }

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }
}
