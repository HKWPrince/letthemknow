package io.letthemknow.channel;

import io.letthemknow.common.tenant.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelConfigRepository extends TenantScopedRepository<ChannelConfig> {

    Optional<ChannelConfig> findByChannelType(ChannelType channelType);

    List<ChannelConfig> findAllByOrderByChannelTypeAsc();
}
