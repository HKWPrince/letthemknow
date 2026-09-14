package io.letthemknow.campaign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.letthemknow.channel.ChannelType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Streams a recipient CSV: header row required, first column = recipient (email or LINE userId),
 * remaining columns → {@code payload_params} JSON. Deduplicates on the normalised identifier and
 * skips rows whose identifier does not match the channel.
 */
@Component
class CsvRecipientParser {

    static final int MAX_ROWS = 200_000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern LINE_USER_ID = Pattern.compile("^U[0-9a-f]{32}$");

    record Row(String identifier, String paramsJson) {}

    record Stats(int totalRows, int accepted, int duplicates, int invalid) {}

    interface RowSink {
        void accept(Row row);
    }

    private final ObjectMapper objectMapper;

    CsvRecipientParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Stats parse(Reader reader, ChannelType channelType, RowSink sink) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .setAllowMissingColumnNames(true)
                .build();
        try (CSVParser parser = format.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            if (headers.isEmpty()) {
                throw new ImportException("CSV header row is missing");
            }
            Set<String> seen = new HashSet<>();
            int total = 0;
            int accepted = 0;
            int duplicates = 0;
            int invalid = 0;
            for (CSVRecord record : parser) {
                total++;
                if (total > MAX_ROWS) {
                    throw new ImportException("CSV exceeds the maximum of " + MAX_ROWS + " rows");
                }
                if (record.size() == 0) {
                    invalid++;
                    continue;
                }
                String identifier = normalise(record.get(0), channelType);
                if (identifier == null) {
                    invalid++;
                    continue;
                }
                if (!seen.add(identifier)) {
                    duplicates++;
                    continue;
                }
                sink.accept(new Row(identifier, params(headers, record)));
                accepted++;
            }
            return new Stats(total, accepted, duplicates, invalid);
        }
    }

    static String normalise(String raw, ChannelType channelType) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (channelType == ChannelType.EMAIL) {
            value = value.toLowerCase(Locale.ROOT);
            return value.length() <= 255 && EMAIL.matcher(value).matches() ? value : null;
        }
        return LINE_USER_ID.matcher(value).matches() ? value : null;
    }

    private String params(List<String> headers, CSVRecord record) {
        if (headers.size() <= 1) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < headers.size() && i < record.size(); i++) {
            String key = headers.get(i);
            if (key == null || key.isBlank()) {
                continue;
            }
            params.put(key.trim(), record.get(i));
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new ImportException("Could not serialise row parameters", e);
        }
    }
}
