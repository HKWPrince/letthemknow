package io.letthemknow.campaign;

import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;

/** 409: the requested event is not allowed from the campaign's current status. */
public class IllegalStateTransitionException extends ApiException {

    public IllegalStateTransitionException(CampaignStatus from, CampaignEvent event) {
        super(ErrorCode.CONFLICT, "Cannot apply " + event + " to a campaign in status " + from);
    }
}
