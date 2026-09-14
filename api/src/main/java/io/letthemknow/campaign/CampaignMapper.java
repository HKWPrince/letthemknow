package io.letthemknow.campaign;

import io.letthemknow.campaign.dto.CampaignDto;
import io.letthemknow.campaign.dto.CampaignRecipientDto;
import org.mapstruct.Mapper;

@Mapper
public interface CampaignMapper {

    CampaignDto toDto(Campaign campaign);

    CampaignRecipientDto toDto(CampaignRecipient recipient);
}
