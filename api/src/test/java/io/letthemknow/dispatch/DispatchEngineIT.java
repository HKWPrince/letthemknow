package io.letthemknow.dispatch;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.letthemknow.campaign.AudienceType;
import io.letthemknow.campaign.Campaign;
import io.letthemknow.campaign.CampaignRecipient;
import io.letthemknow.campaign.CampaignRecipientRepository;
import io.letthemknow.campaign.CampaignRepository;
import io.letthemknow.campaign.CampaignRunner;
import io.letthemknow.campaign.CampaignStatus;
import io.letthemknow.campaign.RecipientStatus;
import io.letthemknow.channel.ChannelConfigService;
import io.letthemknow.channel.ChannelType;
import io.letthemknow.channel.dto.SmtpChannelRequest;
import io.letthemknow.channel.email.EmailSender;
import io.letthemknow.common.tenant.TenantContextHolder;
import io.letthemknow.support.IntegrationTestBase;
import io.letthemknow.support.TestTenants;
import io.letthemknow.template.MessageTemplate;
import io.letthemknow.template.MessageTemplateRepository;
import io.letthemknow.template.TemplateType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.TestPropertySource;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * End-to-end dispatch through Redis Streams with real consumers, GreenMail as the SMTP server and a
 * spied EmailSender for fault injection. Runs in its own Spring context (workers enabled) so no other
 * test context competes for the streams.
 */
@TestPropertySource(properties = {
        "ltk.worker.enabled=true",
        // own Redis namespace so the worker-less context's leftover chunks are never consumed here
        "ltk.dispatch.key-prefix=ltk-engine-test"})
class DispatchEngineIT extends IntegrationTestBase {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("mailer", "secret"))
            .withPerMethodLifecycle(false);

    @SpyBean
    EmailSender emailSender;

    @Autowired TestTenants tenants;
    @Autowired ChannelConfigService channelConfigs;
    @Autowired MessageTemplateRepository templates;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired CampaignRunner runner;
    @Autowired JdbcTemplate jdbc;
    @Autowired DispatchConsumerManager manager;
    @Autowired DispatchStreams streams;
    @Autowired PendingReaper reaper;

    long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(emailSender);
        tenantId = tenants.provision("dispatch").tenant().getId();
        TenantContextHolder.runAs(tenantId, () -> channelConfigs.upsertSmtp(new SmtpChannelRequest(
                "localhost", ServerSetupTest.SMTP.getPort(), "mailer", "secret",
                "noreply@test.local", "Dispatch Test", false, "ops@test.local")));
        greenMail.purgeEmailFromAllMailboxes();
    }

    @AfterEach
    void tearDown() {
        manager.resume();
        Mockito.reset(emailSender);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private long createCampaign(List<String> emails) {
        return TenantContextHolder.runAs(tenantId, () -> {
            MessageTemplate template = templates.save(new MessageTemplate(
                    "t-" + UUID.randomUUID().toString().substring(0, 8), ChannelType.EMAIL, TemplateType.EMAIL_HTML,
                    "Hi {{name}}", "{\"html\":\"<p>Hello {{name}}</p>\",\"text\":\"Hello {{name}}\"}"));
            Campaign campaign = campaigns.save(new Campaign("dispatch " + UUID.randomUUID(), ChannelType.EMAIL,
                    template.getId(), AudienceType.CSV_LIST, null));
            List<Object[]> rows = emails.stream()
                    .map(e -> new Object[]{tenantId, campaign.getId(), e, "{\"name\":\"" + e.split("@")[0] + "\"}"})
                    .toList();
            jdbc.batchUpdate("INSERT INTO campaign_recipients (tenant_id, campaign_id, recipient_identifier, payload_params, status, retry_count) "
                    + "VALUES (?, ?, ?, ?, 'PENDING', 0)", rows);
            return campaign.getId();
        });
    }

    private static List<String> emails(int n) {
        return IntStream.range(0, n).mapToObj(i -> "r" + i + "@test.local").toList();
    }

    private Campaign campaign(long id) {
        return TenantContextHolder.runAs(tenantId, () -> campaigns.findScopedById(id).orElseThrow());
    }

    private CampaignStatus status(long id) {
        return campaign(id).getStatus();
    }

    private Map<RecipientStatus, Long> counts(long id) {
        Map<RecipientStatus, Long> m = new EnumMap<>(RecipientStatus.class);
        TenantContextHolder.runAs(tenantId, () -> recipients.countByStatusForCampaign(id)
                .forEach(c -> m.put(c.getStatus(), c.getCount())));
        return m;
    }

    private List<CampaignRecipient> rows(long id) {
        return TenantContextHolder.runAs(tenantId, () -> recipients.findByCampaignIdOrderByIdAsc(id,
                org.springframework.data.domain.PageRequest.of(0, 1000)).getContent());
    }

    private void awaitStatus(long id, CampaignStatus expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo(expected));
    }

    private void publish(long id) {
        TenantContextHolder.runAs(tenantId, () -> runner.publish(id, null));
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    void thousandRecipientEmailCampaignCompletesWithCorrectCounts() {
        long id = createCampaign(emails(1000));

        publish(id);
        assertThat(status(id)).isEqualTo(CampaignStatus.PROCESSING);
        awaitStatus(id, CampaignStatus.COMPLETED, Duration.ofMinutes(4));

        Campaign done = campaign(id);
        assertThat(done.getTotalCount()).isEqualTo(1000);
        assertThat(done.getSuccessCount()).isEqualTo(1000);
        assertThat(done.getFailedCount()).isZero();
        assertThat(done.getStartedAt()).isNotNull();
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(counts(id)).containsEntry(RecipientStatus.SENT, 1000L).doesNotContainKeys(RecipientStatus.PENDING, RecipientStatus.SENDING);
        assertThat(greenMail.getReceivedMessages()).hasSize(1000);
        assertThat(rows(id).get(0).getExternalMessageId()).isNotBlank();
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() {
        long id = createCampaign(List.of("ok1@test.local", "flaky@test.local", "ok2@test.local"));
        AtomicInteger flakyCalls = new AtomicInteger();
        doAnswer(inv -> {
            if ("flaky@test.local".equals(inv.getArgument(1)) && flakyCalls.getAndIncrement() == 0) {
                throw new MailSendException("temporary", new MessagingException("io", new SocketTimeoutException("read timed out")));
            }
            return inv.callRealMethod();
        }).when(emailSender).send(anyLong(), anyString(), any(), any(), any());

        publish(id);
        awaitStatus(id, CampaignStatus.COMPLETED, Duration.ofSeconds(60));

        assertThat(flakyCalls.get()).isEqualTo(2);
        assertThat(campaign(id).getSuccessCount()).isEqualTo(3);
        assertThat(counts(id)).containsEntry(RecipientStatus.SENT, 3L);
        assertThat(greenMail.getReceivedMessages()).hasSize(3);
        CampaignRecipient flaky = rows(id).stream().filter(r -> r.getRecipientIdentifier().startsWith("flaky")).findFirst().orElseThrow();
        assertThat(flaky.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(flaky.getErrorCode()).isNull();
    }

    @Test
    void exhaustedTransientRetriesEndAsFailed() {
        long id = createCampaign(List.of("always@test.local"));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            calls.incrementAndGet();
            throw new MailSendException("temporary", new MessagingException("io", new SocketTimeoutException("timeout")));
        }).when(emailSender).send(anyLong(), anyString(), any(), any(), any());

        publish(id);
        awaitStatus(id, CampaignStatus.AWAITING_RESOLUTION, Duration.ofSeconds(60));

        assertThat(calls.get()).isEqualTo(3);
        CampaignRecipient row = rows(id).get(0);
        assertThat(row.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(row.getErrorCode()).isEqualTo("NETWORK");
        assertThat(row.getErrorMessage()).startsWith("Gave up after 3 attempts");
        assertThat(campaign(id).getFailedCount()).isEqualTo(1);
    }

    @Test
    void terminalFailureAwaitsResolutionAndRetryFailedCompletes() throws Exception {
        long id = createCampaign(List.of("good@test.local", "bad@test.local"));
        doAnswer(inv -> {
            if ("bad@test.local".equals(inv.getArgument(1))) {
                throw new MailSendException(Map.of(new Object(), new SMTPAddressFailedException(
                        new InternetAddress("bad@test.local"), "RCPT TO", 550, "5.1.1 user unknown")));
            }
            return inv.callRealMethod();
        }).when(emailSender).send(anyLong(), anyString(), any(), any(), any());

        publish(id);
        awaitStatus(id, CampaignStatus.AWAITING_RESOLUTION, Duration.ofSeconds(60));

        Campaign awaiting = campaign(id);
        assertThat(awaiting.getSuccessCount()).isEqualTo(1);
        assertThat(awaiting.getFailedCount()).isEqualTo(1);
        CampaignRecipient bad = rows(id).stream().filter(r -> r.getRecipientIdentifier().startsWith("bad")).findFirst().orElseThrow();
        assertThat(bad.getStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(bad.getErrorCode()).isEqualTo("SMTP_INVALID_ADDRESS");
        assertThat(bad.getErrorMessage()).contains("5.1.1");

        // operator fixes the cause (here: the fault disappears) and retries
        Mockito.reset(emailSender);
        var dto = TenantContextHolder.runAs(tenantId, () -> runner.retryFailed(id));
        assertThat(dto.status()).isEqualTo(CampaignStatus.RETRYING);
        awaitStatus(id, CampaignStatus.COMPLETED, Duration.ofSeconds(60));

        Campaign done = campaign(id);
        assertThat(done.getSuccessCount()).isEqualTo(2);
        assertThat(done.getFailedCount()).isZero();
        CampaignRecipient retried = rows(id).stream().filter(r -> r.getRecipientIdentifier().startsWith("bad")).findFirst().orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
    }

    @Test
    void abortMidRunLeavesNoPendingRecipients() {
        long id = createCampaign(emails(200));
        doAnswer(inv -> {
            Thread.sleep(20);
            return inv.callRealMethod();
        }).when(emailSender).send(anyLong(), anyString(), any(), any(), any());

        publish(id);
        await().atMost(Duration.ofSeconds(30)).until(() -> counts(id).getOrDefault(RecipientStatus.SENT, 0L) > 0);
        var aborted = TenantContextHolder.runAs(tenantId, () -> runner.abort(id));
        assertThat(aborted.status()).isEqualTo(CampaignStatus.TERMINATED);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            Map<RecipientStatus, Long> c = counts(id);
            assertThat(c.getOrDefault(RecipientStatus.PENDING, 0L)).isZero();
            assertThat(c.getOrDefault(RecipientStatus.SENDING, 0L)).isZero();
        });

        Map<RecipientStatus, Long> c = counts(id);
        long sent = c.getOrDefault(RecipientStatus.SENT, 0L);
        long cancelled = c.getOrDefault(RecipientStatus.CANCELLED, 0L);
        assertThat(sent).isPositive();
        assertThat(cancelled).isPositive();
        assertThat(sent + cancelled).isEqualTo(200);
        assertThat(status(id)).isEqualTo(CampaignStatus.TERMINATED);
        assertThat(greenMail.getReceivedMessages()).hasSize((int) sent);
    }

    @Test
    void reaperRecoversChunkOfCrashedConsumerWithoutDuplicateSends() throws Exception {
        manager.pause();
        Thread.sleep(2500); // let any in-flight XREADGROUP (2s timeout) return before we enqueue
        long id = createCampaign(emails(5));
        publish(id);

        // "dead" consumer reads the entry and crashes: entry stays pending, no ACK
        var entries = streams.stream(ChannelType.EMAIL).readGroup(DispatchStreams.GROUP, "dead-consumer",
                StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(2)));
        assertThat(entries).hasSize(1);
        StreamMessageId entryId = entries.keySet().iterator().next();

        // simulate partial progress before the crash: 2 already SENT (recorded), 1 claimed but never sent
        List<CampaignRecipient> before = rows(id);
        jdbc.update("UPDATE campaign_recipients SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(6) WHERE id IN (?, ?)",
                before.get(0).getId(), before.get(1).getId());
        jdbc.update("UPDATE campaign_recipients SET status = 'SENDING' WHERE id = ?", before.get(2).getId());

        Thread.sleep(2500); // exceed ltk.dispatch.reaper-idle (2s)
        int recovered = reaper.reap();

        assertThat(recovered).isEqualTo(1);
        assertThat(greenMail.getReceivedMessages()).as("only the 3 unsent recipients are delivered").hasSize(3);
        assertThat(counts(id)).containsEntry(RecipientStatus.SENT, 5L);
        assertThat(status(id)).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(campaign(id).getSuccessCount()).isEqualTo(5);
        assertThat(streams.stream(ChannelType.EMAIL).getPendingInfo(DispatchStreams.GROUP).getTotal())
                .as("entry %s acknowledged", entryId).isZero();
    }

    @Test
    void scheduledCampaignStartsWhenDueAndScheduledAbortCancelsEntry() {
        long due = createCampaign(emails(2));
        var scheduled = TenantContextHolder.runAs(tenantId,
                () -> runner.publish(due, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3)));
        assertThat(scheduled.status()).isEqualTo(CampaignStatus.SCHEDULED);
        awaitStatus(due, CampaignStatus.COMPLETED, Duration.ofSeconds(60));
        assertThat(campaign(due).getSuccessCount()).isEqualTo(2);

        long later = createCampaign(emails(1));
        TenantContextHolder.runAs(tenantId, () -> runner.publish(later, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));
        assertThat(streams.scheduleDelayedQueue().contains(later)).isTrue();
        var aborted = TenantContextHolder.runAs(tenantId, () -> runner.abort(later));
        assertThat(aborted.status()).isEqualTo(CampaignStatus.TERMINATED);
        assertThat(streams.scheduleDelayedQueue().contains(later)).isFalse();
        assertThat(counts(later)).containsEntry(RecipientStatus.CANCELLED, 1L);
    }
}
