package com.example.eventsim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MetricsRegistry {
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> types = new ConcurrentHashMap<>();

    public void incrementCounter(String name) {
        incrementCounter(name, Map.of(), 1L);
    }

    public void incrementCounter(String name, long amount) {
        incrementCounter(name, Map.of(), amount);
    }

    public void incrementCounter(String name, Map<String, String> labels, long amount) {
        types.putIfAbsent(name, "counter");
        counters.computeIfAbsent(key(name, labels), ignored -> new AtomicLong()).addAndGet(amount);
    }

    public void setGauge(String name, long value) {
        setGauge(name, Map.of(), value);
    }

    public void setGauge(String name, Map<String, String> labels, long value) {
        types.putIfAbsent(name, "gauge");
        gauges.computeIfAbsent(key(name, labels), ignored -> new AtomicLong()).set(value);
    }

    public String renderPrometheus() {
        List<String> lines = new ArrayList<>();
        types.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    lines.add("# TYPE " + entry.getKey() + " " + entry.getValue());
                    emitMetricLines(lines, entry.getKey(), entry.getValue());
                });
        return String.join("\n", lines) + "\n";
    }

    private void emitMetricLines(List<String> lines, String metricName, String type) {
        ConcurrentHashMap<String, AtomicLong> source = "counter".equals(type) ? counters : gauges;
        source.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(metricName + "|"))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    lines.add(metricName + parts[1] + " " + entry.getValue().get());
                });
    }

    private String key(String name, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return name + "|";
        }
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> joiner.add(entry.getKey() + "=\"" + entry.getValue() + "\""));
        return name + "|" + joiner;
    }
}
