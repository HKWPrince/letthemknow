package io.letthemknow.campaign;

import io.letthemknow.channel.ChannelType;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignStateMachineTest {

    /** The specification from CLAUDE.md §3.6, written independently of the implementation table. */
    private static final Map<CampaignStatus, Map<CampaignEvent, CampaignStatus>> SPEC = Map.of(
            DRAFT, Map.of(SCHEDULE, SCHEDULED, START, PROCESSING, TERMINATE, TERMINATED),
            SCHEDULED, Map.of(START, PROCESSING, TERMINATE, TERMINATED),
            PROCESSING, Map.of(COMPLETE, COMPLETED, NEED_RESOLUTION, AWAITING_RESOLUTION, TERMINATE, TERMINATED),
            AWAITING_RESOLUTION, Map.of(RETRY, RETRYING, TERMINATE, TERMINATED),
            RETRYING, Map.of(COMPLETE, COMPLETED, NEED_RESOLUTION, AWAITING_RESOLUTION, TERMINATE, TERMINATED),
            COMPLETED, Map.of(),
            TERMINATED, Map.of());

    private final CampaignStateMachine machine = new CampaignStateMachine();

    private static Campaign campaignIn(CampaignStatus status) {
        Campaign c = new Campaign("t", ChannelType.EMAIL, 1L, AudienceType.CSV_LIST, null);
        c.setStatus(status);
        return c;
    }

    @Test
    void everyStatusEventPairMatchesSpecification() {
        int legal = 0;
        int illegal = 0;
        for (CampaignStatus from : CampaignStatus.values()) {
            for (CampaignEvent event : CampaignEvent.values()) {
                CampaignStatus expected = SPEC.get(from).get(event);
                Campaign campaign = campaignIn(from);
                if (expected == null) {
                    assertThat(machine.canFire(from, event)).as("%s + %s", from, event).isFalse();
                    assertThat(machine.next(from, event)).isEmpty();
                    assertThatThrownBy(() -> machine.transition(campaign, event))
                            .as("%s + %s must be rejected", from, event)
                            .isInstanceOf(IllegalStateTransitionException.class)
                            .satisfies(e -> assertThat(((IllegalStateTransitionException) e).code().status()).isEqualTo(409));
                    assertThat(campaign.getStatus()).as("status unchanged after rejection").isEqualTo(from);
                    illegal++;
                } else {
                    assertThat(machine.canFire(from, event)).as("%s + %s", from, event).isTrue();
                    assertThat(machine.next(from, event)).contains(expected);
                    assertThat(machine.transition(campaign, event)).isEqualTo(expected);
                    assertThat(campaign.getStatus()).isEqualTo(expected);
                    legal++;
                }
            }
        }
        assertThat(legal).isEqualTo(13);
        assertThat(illegal).isEqualTo(CampaignStatus.values().length * CampaignEvent.values().length - 13);
    }

    @Test
    void startAndFinishStampTimestamps() {
        Campaign campaign = campaignIn(DRAFT);
        machine.transition(campaign, START);
        assertThat(campaign.getStartedAt()).isNotNull();
        assertThat(campaign.getFinishedAt()).isNull();

        machine.transition(campaign, NEED_RESOLUTION);
        assertThat(campaign.getFinishedAt()).isNotNull();

        machine.transition(campaign, RETRY);
        assertThat(campaign.getStatus()).isEqualTo(RETRYING);
        assertThat(campaign.getStatus().isProcessing()).isTrue();
        assertThat(campaign.getFinishedAt()).isNull();

        machine.transition(campaign, COMPLETE);
        assertThat(campaign.getFinishedAt()).isNotNull();
        assertThat(campaign.getStatus().isFinal()).isTrue();
    }

    @Test
    void completionRuleDependsOnFailedCount() {
        assertThat(CampaignStateMachine.completionEvent(0)).isEqualTo(COMPLETE);
        assertThat(CampaignStateMachine.completionEvent(1)).isEqualTo(NEED_RESOLUTION);
        assertThat(Optional.of(PROCESSING).map(s -> machine.next(s, CampaignStateMachine.completionEvent(3)).orElseThrow()))
                .contains(AWAITING_RESOLUTION);
    }
}
