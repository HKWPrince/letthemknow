package io.letthemknow.template;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface MessageTemplateRepository extends TenantScopedRepository<MessageTemplate> {

    Optional<MessageTemplate> findByName(String name);

    boolean existsByName(String name);

    Page<MessageTemplate> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Page<MessageTemplate> findAllByChannelTypeOrderByUpdatedAtDesc(ChannelType channelType, Pageable pageable);
}
