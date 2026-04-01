package com.example.eventsim;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MapUtil {
    private MapUtil() {
    }

    public static Map<String, String> labels(String key, String value) {
        return Map.of(key, value);
    }

    public static Map<String, String> labels(String key1, String value1, String key2, String value2) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(key1, value1);
        labels.put(key2, value2);
        return Map.copyOf(labels);
    }
}
