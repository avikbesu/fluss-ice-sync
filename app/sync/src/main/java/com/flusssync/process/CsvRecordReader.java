package com.flusssync.process;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Streams RFC 4180-style delimited records (one record may span multiple
 * physical lines when a field is quoted and contains a newline) out of a
 * {@link Reader}, one record at a time.
 *
 * <p>If {@code quoteChar} is empty, quoting is disabled entirely and each
 * physical line is one record, split naively on the delimiter.
 */
public final class CsvRecordReader implements Closeable {

    private final Reader reader;
    private final char delimiter;
    private final Character quoteChar;
    private final Character escapeChar;
    private int pushedBack = -2;

    public CsvRecordReader(Reader reader, char delimiter, String quoteChar, String escapeChar) {
        this.reader = reader;
        this.delimiter = delimiter;
        this.quoteChar = (quoteChar == null || quoteChar.isEmpty()) ? null : quoteChar.charAt(0);
        this.escapeChar = (escapeChar == null || escapeChar.isEmpty()) ? null : escapeChar.charAt(0);
    }

    /** @return the next record, or {@code null} at end of stream. */
    public List<String> next() {
        try {
            return quoteChar == null ? nextSimple() : nextQuoted();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> nextSimple() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            line.append((char) c);
        }
        if (!any) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        for (String field : line.toString().split(String.valueOf(delimiter), -1)) {
            fields.add(field);
        }
        return fields;
    }

    private List<String> nextQuoted() throws IOException {
        int first = read();
        if (first == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int c = first;
        while (true) {
            if (c == -1) {
                fields.add(field.toString());
                break;
            }
            if (inQuotes) {
                if (c == quoteChar) {
                    int next = read();
                    if (next == quoteChar) {
                        field.append((char) quoteChar);
                    } else if (escapeChar != null && quoteChar.equals(escapeChar) && next != quoteChar) {
                        // closing quote
                        inQuotes = false;
                        pushBack(next);
                    } else {
                        inQuotes = false;
                        pushBack(next);
                    }
                } else {
                    field.append((char) c);
                }
            } else {
                if (c == quoteChar && field.length() == 0) {
                    inQuotes = true;
                } else if (c == delimiter) {
                    fields.add(field.toString());
                    field = new StringBuilder();
                } else if (c == '\n') {
                    fields.add(field.toString());
                    return fields;
                } else if (c == '\r') {
                    // skip, wait for \n
                } else {
                    field.append((char) c);
                }
            }
            c = read();
        }
        return fields;
    }

    private int read() throws IOException {
        if (pushedBack != -2) {
            int v = pushedBack;
            pushedBack = -2;
            return v;
        }
        return reader.read();
    }

    private void pushBack(int c) {
        pushedBack = c;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    /** Iterator view for convenience, e.g. in a for-each loop. */
    public java.util.Iterator<List<String>> iterator() {
        return new java.util.Iterator<>() {
            private List<String> buffered = next();

            @Override
            public boolean hasNext() {
                return buffered != null;
            }

            @Override
            public List<String> next() {
                if (buffered == null) {
                    throw new NoSuchElementException();
                }
                List<String> current = buffered;
                buffered = CsvRecordReader.this.next();
                return current;
            }
        };
    }
}
