package io.letthemknow.campaign;

import io.letthemknow.channel.ChannelType;
import io.letthemknow.common.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs on the import executor: parses the CSV, replaces the campaign's recipients with JDBC batch
 * inserts (500 rows per batch) inside one transaction, then flips {@code import_status}.
 */
@Component
class RecipientImportWorker {

    static final int BATCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(RecipientImportWorker.class);
    private static final String INSERT_SQL = """
            INSERT INTO campaign_recipients
              (tenant_id, campaign_id, recipient_identifier, payload_params, status, retry_count)
            VALUES (?, ?, ?, ?, 'PENDING', 0)
            """;
    private static final String STATUS_SQL = """
            UPDATE campaigns SET import_status = ?, import_error = ?, total_count = ?, updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = ? AND tenant_id = ?
            """;

    private final CsvRecipientParser parser;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    RecipientImportWorker(CsvRecipientParser parser, JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.parser = parser;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(txManager);
    }

    @Async("importExecutor")
    public void importFile(long tenantId, long campaignId, ChannelType channelType, Path file) {
        TenantContextHolder.runAs(tenantId, () -> run(tenantId, campaignId, channelType, file));
    }

    /** Synchronous body, also used directly by tests. */
    void run(long tenantId, long campaignId, ChannelType channelType, Path file) {
        try {
            CsvRecipientParser.Stats stats = transaction.execute(status -> {
                jdbc.update("DELETE FROM campaign_recipients WHERE campaign_id = ? AND tenant_id = ?", campaignId, tenantId);
                CsvRecipientParser.Stats parsed;
                try {
                    parsed = insertAll(tenantId, campaignId, channelType, file);
                } catch (IOException e) {
                    throw new ImportException("CSV could not be read: " + e.getMessage(), e);
                }
                jdbc.update(STATUS_SQL, ImportStatus.READY.name(), null, parsed.accepted(), campaignId, tenantId);
                return parsed;
            });
            log.info("Imported recipients for campaign {} (tenant {}): accepted={} duplicates={} invalid={} rows={}",
                    campaignId, tenantId, stats.accepted(), stats.duplicates(), stats.invalid(), stats.totalRows());
        } catch (Exception e) {
            String message = e instanceof ImportException ? e.getMessage() : "Import failed: " + e.getMessage();
            log.warn("Recipient import failed for campaign {} (tenant {}): {}", campaignId, tenantId, message);
            jdbc.update(STATUS_SQL, ImportStatus.FAILED.name(), truncate(message), 0, campaignId, tenantId);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temp file cleanup is best effort
            }
        }
    }

    private CsvRecipientParser.Stats insertAll(long tenantId, long campaignId, ChannelType channelType, Path file)
            throws IOException {
        List<CsvRecipientParser.Row> batch = new ArrayList<>(BATCH_SIZE);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CsvRecipientParser.Stats stats = parser.parse(reader, channelType, row -> {
                batch.add(row);
                if (batch.size() >= BATCH_SIZE) {
                    flush(tenantId, campaignId, batch);
                }
            });
            flush(tenantId, campaignId, batch);
            return stats;
        }
    }

    private void flush(long tenantId, long campaignId, List<CsvRecipientParser.Row> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Object[]> args = batch.stream()
                .map(r -> new Object[]{tenantId, campaignId, r.identifier(), r.paramsJson()})
                .toList();
        jdbc.batchUpdate(INSERT_SQL, args, new int[]{Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.VARCHAR});
        batch.clear();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
