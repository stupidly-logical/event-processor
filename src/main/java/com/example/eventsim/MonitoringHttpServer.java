package com.example.eventsim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MonitoringHttpServer {
    private final HttpServer server;

    public MonitoringHttpServer(int port,
                                MetricsRegistry metrics,
                                SimulationClock clock,
                                SimulatedCircuitBreaker circuitBreaker,
                                InMemoryPubSub pubSub,
                                DeadLetterQueue dlq,
                                ScenarioController scenarioController) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> writeResponse(exchange, 200, metrics.renderPrometheus(), "text/plain; version=0.0.4"));
        server.createContext("/health", exchange -> writeResponse(exchange, 200, "ok", "text/plain"));
        server.createContext("/api/state", exchange -> {
            List<Map<String, Object>> dlqEntries = dlq.snapshot().stream()
                    .limit(20)
                    .map(entry -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("reason", entry.reason().name());
                        map.put("timestampMs", entry.timestampMs());
                        map.put("retryCount", entry.retryCount());
                        map.put("idempotencyKey", entry.unit().idempotencyKey());
                        map.put("eventType", entry.unit().event().type());
                        map.put("repo", entry.unit().event().repo().name());
                        return map;
                    })
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("simulatedNowMs", clock.now());
            body.put("circuitBreakerState", circuitBreaker.state().name());
            body.put("pubSub", pubSub.status());
            body.put("scenarioPhase", scenarioController.phase());
            body.put("dlqDepth", dlq.snapshot().size());
            body.put("dlqPreview", dlqEntries);
            writeResponse(exchange, 200, JsonUtil.toJson(body), "application/json");
        });
    }

    public void start() {
        server.start();
    }

    private void writeResponse(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
