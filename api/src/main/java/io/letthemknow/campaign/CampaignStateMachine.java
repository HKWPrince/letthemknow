package io.letthemknow.campaign;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static io.letthemknow.campaign.CampaignEvent.COMPLETE;
import static io.letthemknow.campaign.CampaignEvent.NEED_RESOLUTION;
import static io.letthemknow.campaign.CampaignEvent.RETRY;
import static io.letthemknow.campaign.CampaignEvent.SCHEDULE;
import static io.letthemknow.campaign.CampaignEvent.START;
import static io.letthemknow.campaign.CampaignEvent.TERMINATE;
import static io.letthemknow.campaign.CampaignStatus.AWAITING_RESOLUTION;
import static io.letthemknow.campaign.CampaignStatus.COMPLETED;
import static io.letthemknow.campaign.CampaignStatus.DRAFT;
import static io.letthemknow.campaign.CampaignStatus.PROCESSING;
import static io.letthemknow.campaign.CampaignStatus.RETRYING;
import static io.letthemknow.campaign.CampaignStatus.SCHEDULED;
import static io.letthemknow.campaign.CampaignStatus.TERMINATED;

/**
 * The only place campaign status changes. Allowed transitions (CLAUDE.md §3.6):
 * <pre>
 * DRAFT               → SCHEDULED | PROCESSING | TERMINATED
 * SCHEDULED           → PROCESSING | TERMINATED
 * PROCESSING          → COMPLETED | AWAITING_RESOLUTION | TERMINATED
 * AWAITING_RESOLUTION → RETRYING | TERMINATED
 * RETRYING            → COMPLETED | AWAITING_RESOLUTION | TERMINATED
 * </pre>
 */
@Component
public class CampaignStateMachine {

    private static final Map<CampaignStatus, Map<CampaignEvent, CampaignStatus>> TRANSITIONS = new EnumMap<>(CampaignStatus.class);

    static {
        TRANSITIONS.put(DRAFT, Map.of(SCHEDULE, SCHEDULED, START, PROCESSING, TERMINATE, TERMINATED));
        TRANSITIONS.put(SCHEDULED, Map.of(START, PROCESSING, TERMINATE, TERMINATED));
        TRANSITIONS.put(PROCESSING, Map.of(COMPLETE, COMPLETED, NEED_RESOLUTION, AWAITING_RESOLUTION, TERMINATE, TERMINATED));
        TRANSITIONS.put(AWAITING_RESOLUTION, Map.of(RETRY, RETRYING, TERMINATE, TERMINATED));
        TRANSITIONS.put(RETRYING, Map.of(COMPLETE, COMPLETED, NEED_RESOLUTION, AWAITING_RESOLUTION, TERMINATE, TERMINATED));
        TRANSITIONS.put(COMPLETED, Map.of());
        TRANSITIONS.put(TERMINATED, Map.of());
    }

    public Optional<CampaignStatus> next(CampaignStatus from, CampaignEvent event) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(from, Map.of()).get(event));
    }

    public boolean canFire(CampaignStatus from, CampaignEvent event) {
        return next(from, event).isPresent();
    }

    /** Applies the event, updating status and the started/finished timestamps. */
    public CampaignStatus transition(Campaign campaign, CampaignEvent event) {
        CampaignStatus from = campaign.getStatus();
        CampaignStatus to = next(from, event).orElseThrow(() -> new IllegalStateTransitionException(from, event));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        switch (event) {
            case START -> {
                campaign.setStartedAt(now);
                campaign.setFinishedAt(null);
            }
            case RETRY -> campaign.setFinishedAt(null);
            case COMPLETE, NEED_RESOLUTION, TERMINATE -> campaign.setFinishedAt(now);
            default -> { }
        }
        campaign.setStatus(to);
        return to;
    }

    /** Completion rule: {@code failed_count == 0 ? COMPLETED : AWAITING_RESOLUTION}. */
    public static CampaignEvent completionEvent(int failedCount) {
        return failedCount == 0 ? COMPLETE : NEED_RESOLUTION;
    }
}
