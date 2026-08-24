package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineParserTest {

    private final ApplicationConfig.Parsing parsing = new ApplicationConfig.Parsing();

    private SyncSourceConfig.Format format() {
        SyncSourceConfig.Format format = new SyncSourceConfig.Format();
        format.delimiter = ",";
        format.quoteChar = "\"";
        format.escapeChar = "\"";
        format.hasHeader = true;
        SyncSourceConfig.Column id = new SyncSourceConfig.Column();
        id.name = "order_id";
        id.type = SyncSourceConfig.ColumnType.STRING;
        SyncSourceConfig.Column ts = new SyncSourceConfig.Column();
        ts.name = "order_ts";
        ts.type = SyncSourceConfig.ColumnType.TIMESTAMP;
        SyncSourceConfig.Column amount = new SyncSourceConfig.Column();
        amount.name = "amount_cents";
        amount.type = SyncSourceConfig.ColumnType.BIGINT;
        format.columns = List.of(id, ts, amount);
        return format;
    }

    @Test
    void parsesSimpleDelimitedRecord() {
        SyncSourceConfig.Format format = format();
        CsvRecordReader csv = new CsvRecordReader(new StringReader("order_id,order_ts,amount_cents\nA1,2026-01-02T03:04:05,1234\n"),
                ',', format.quoteChar, format.escapeChar);
        List<String> header = csv.next();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, header);
        LineParser parser = new LineParser(format, parsing);

        Row row = parser.toRow(csv.next(), index);

        assertThat(row.get("order_id")).isEqualTo("A1");
        assertThat(row.get("order_ts")).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertThat(row.get("amount_cents")).isEqualTo(1234L);
    }

    @Test
    void quotedFieldMaySpanNewlineAndContainDelimiter() {
        SyncSourceConfig.Format format = format();
        String content = "order_id,order_ts,amount_cents\n\"A,1\nline2\",2026-01-02T03:04:05,10\n";
        CsvRecordReader csv = new CsvRecordReader(new StringReader(content), ',', format.quoteChar, format.escapeChar);
        List<String> header = csv.next();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, header);
        LineParser parser = new LineParser(format, parsing);

        Row row = parser.toRow(csv.next(), index);

        assertThat(row.get("order_id")).isEqualTo("A,1\nline2");
    }

    @Test
    void disablingQuoteCharSplitsNaively() {
        SyncSourceConfig.Format format = format();
        format.quoteChar = "";
        CsvRecordReader csv = new CsvRecordReader(new StringReader("order_id,order_ts,amount_cents\n\"A1\",2026-01-02T03:04:05,10\n"),
                ',', format.quoteChar, format.escapeChar);
        List<String> header = csv.next();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, header);
        LineParser parser = new LineParser(format, parsing);

        Row row = parser.toRow(csv.next(), index);

        assertThat(row.get("order_id")).isEqualTo("\"A1\"");
    }

    @Test
    void nullLiteralBecomesNull() {
        SyncSourceConfig.Format format = format();
        CsvRecordReader csv = new CsvRecordReader(new StringReader("order_id,order_ts,amount_cents\n,2026-01-02T03:04:05,10\n"),
                ',', format.quoteChar, format.escapeChar);
        List<String> header = csv.next();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, header);
        LineParser parser = new LineParser(format, parsing);

        Row row = parser.toRow(csv.next(), index);

        assertThat(row.get("order_id")).isNull();
    }

    @Test
    void invalidValueThrowsRowParseException() {
        SyncSourceConfig.Format format = format();
        CsvRecordReader csv = new CsvRecordReader(new StringReader("order_id,order_ts,amount_cents\nA1,2026-01-02T03:04:05,not-a-number\n"),
                ',', format.quoteChar, format.escapeChar);
        List<String> header = csv.next();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, header);
        LineParser parser = new LineParser(format, parsing);
        List<String> record = csv.next();

        assertThatThrownBy(() -> parser.toRow(record, index))
                .isInstanceOf(LineParser.RowParseException.class);
    }

    @Test
    void buildFieldIndexReturnsNullWhenHeaderMissingConfiguredColumn() {
        SyncSourceConfig.Format format = format();
        Map<String, Integer> index = LineParser.buildFieldIndex(format, List.of("order_id", "amount_cents"));

        assertThat(index).isNull();
    }
}
