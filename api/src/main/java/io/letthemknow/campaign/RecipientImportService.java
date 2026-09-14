package io.letthemknow.campaign;

import io.letthemknow.campaign.dto.CampaignDto;
import io.letthemknow.common.ApiException;
import io.letthemknow.common.ErrorCode;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Accepts the multipart upload (≤ 20 MB, DRAFT + CSV_LIST only), marks the campaign IMPORTING and
 * hands a temp copy of the file to {@link RecipientImportWorker} on the async executor.
 * A new upload replaces the campaign's existing recipient list.
 */
@Service
public class RecipientImportService {

    static final long MAX_BYTES = 20L * 1024 * 1024;

    private final CampaignService campaignService;
    private final CampaignRepository campaigns;
    private final CampaignMapper mapper;
    private final RecipientImportWorker worker;

    public RecipientImportService(CampaignService campaignService, CampaignRepository campaigns,
                                  CampaignMapper mapper, RecipientImportWorker worker) {
        this.campaignService = campaignService;
        this.campaigns = campaigns;
        this.mapper = mapper;
        this.worker = worker;
    }

    @Transactional
    public CampaignDto start(Long campaignId, MultipartFile file) {
        Campaign campaign = campaignService.require(campaignId);
        CampaignService.requireDraft(campaign);
        if (campaign.getTargetAudienceType() != AudienceType.CSV_LIST) {
            throw new ApiException(ErrorCode.CONFLICT, "Recipients can only be uploaded for CSV_LIST campaigns");
        }
        if (campaign.getImportStatus() == ImportStatus.IMPORTING) {
            throw new ApiException(ErrorCode.CONFLICT, "An import is already running for this campaign");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "CSV file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.UNPROCESSABLE, "CSV exceeds the 20 MB limit");
        }

        Path temp;
        try {
            temp = Files.createTempFile("ltk-recipients-", ".csv");
            file.transferTo(temp);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not store uploaded file", e);
        }

        campaign.setImportStatus(ImportStatus.IMPORTING);
        campaign.setImportError(null);
        Campaign saved = campaigns.saveAndFlush(campaign);

        long tenantId = TenantContextHolder.require();
        worker.importFile(tenantId, campaignId, campaign.getChannelType(), temp);
        return mapper.toDto(saved);
    }
}
