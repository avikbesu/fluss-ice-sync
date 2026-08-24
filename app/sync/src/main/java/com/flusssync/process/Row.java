package com.flusssync.process;

import java.util.LinkedHashMap;
import java.util.Map;

/** One parsed, typed data row, keyed by configured column name in column order. */
public final class Row {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public void put(String column, Object value) {
        values.put(column, value);
    }

    public Object get(String column) {
        return values.get(column);
    }

    public Map<String, Object> values() {
        return values;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
