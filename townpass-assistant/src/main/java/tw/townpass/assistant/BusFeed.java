package tw.townpass.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;

@Component
class BusFeed {
    private static final Duration FRESHNESS = Duration.ofMinutes(3);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Clock clock;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private Topology topology;
    private Instant topologyExpires = Instant.MIN;
    private Snapshot snapshot;
    private Instant nextRefresh = Instant.MIN;

    BusFeed(Clock clock, ObjectMapper mapper,
            @Value("${assistant.bus.base-url:https://tcgbusfs.blob.core.windows.net/blobbus/}") String baseUrl) {
        this.clock = clock;
        this.mapper = mapper;
        this.baseUrl = baseUrl;
    }

    synchronized Topology topology() {
        Instant now = clock.instant();
        if (topology != null && now.isBefore(topologyExpires)) {
            return topology;
        }
        try {
            Map<Long, Route> routes = new LinkedHashMap<>();
            for (JsonNode row : rows(fetch("Route"))) {
                long id = row.path("Id").asLong();
                String name = row.path("nameZh").asText();
                if (id > 0 && !name.isBlank()) {
                    routes.putIfAbsent(id, new Route(id, name, row.path("departureZh").asText(),
                            row.path("destinationZh").asText()));
                }
            }
            List<Stop> stops = new ArrayList<>();
            for (JsonNode row : rows(fetch("Stop"))) {
                long id = row.path("Id").asLong();
                long routeId = row.path("routeId").asLong();
                int direction = row.path("goBack").asInt(-1);
                String name = row.path("nameZh").asText();
                if (id > 0 && routes.containsKey(routeId) && !name.isBlank()
                        && (direction == 0 || direction == 1)) {
                    stops.add(new Stop(id, routeId, name, direction));
                }
            }
            if (routes.isEmpty() || stops.isEmpty()) {
                throw new IllegalArgumentException("Empty bus topology");
            }
            topology = new Topology(List.copyOf(routes.values()), List.copyOf(stops));
            topologyExpires = now.plus(Duration.ofHours(6));
            return topology;
        } catch (RuntimeException ex) {
            topologyExpires = now.plusSeconds(15);
            if (topology != null) {
                return topology;
            }
            throw new Unavailable();
        }
    }

    synchronized Snapshot arrivals() {
        Instant now = clock.instant();
        if (!now.isBefore(nextRefresh)) {
            nextRefresh = now.plusSeconds(20);
            try {
                JsonNode document = fetch("EstimateTime");
                Instant update = LocalDateTime.parse(
                        document.path("EssentialInfo").path("UpdateTime").asText(), SOURCE_TIME)
                        .atZone(TAIPEI).toInstant();
                Map<StopKey, Integer> estimates = new LinkedHashMap<>();
                for (JsonNode row : rows(document)) {
                    JsonNode estimate = row.path("EstimateTime");
                    if (!estimate.asText().matches("-?[0-9]{1,8}")) {
                        continue;
                    }
                    long route = row.path("RouteID").asLong();
                    long stop = row.path("StopID").asLong();
                    if (route > 0 && stop > 0) {
                        estimates.merge(new StopKey(route, stop), estimate.asInt(),
                                BusFeed::preferredEstimate);
                    }
                }
                if (!estimates.isEmpty()) {
                    snapshot = new Snapshot(update, Map.copyOf(estimates));
                }
            } catch (RuntimeException ex) {
                // A previous snapshot is usable only while its upstream timestamp is fresh.
            }
        }
        now = clock.instant();
        if (snapshot == null || snapshot.updatedAt().isBefore(now.minus(FRESHNESS))
                || snapshot.updatedAt().isAfter(now.plusSeconds(60))) {
            throw new Unavailable();
        }
        return snapshot;
    }

    private static int preferredEstimate(int first, int second) {
        if (first < 0 && second < 0) {
            return first;
        }
        if (first < 0 || second < 0) {
            return Math.max(first, second);
        }
        return Math.min(first, second);
    }

    private JsonNode rows(JsonNode document) {
        JsonNode rows = document.path("BusInfo");
        if (!rows.isArray()) {
            throw new IllegalArgumentException("Missing BusInfo");
        }
        return rows;
    }

    JsonNode fetch(String feed) {
        URI uri = URI.create(baseUrl + "Get" + feed + ".gz");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new Unavailable();
            }
            try (InputStream data = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
                return mapper.readTree(data);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (IOException ex) {
            throw new Unavailable();
        }
    }

    record Route(long id, String name, String departure, String destination) {}
    record Stop(long id, long routeId, String name, int direction) {}
    record StopKey(long routeId, long stopId) {}
    record Topology(List<Route> routes, List<Stop> stops) {}
    record Snapshot(Instant updatedAt, Map<StopKey, Integer> estimates) {}
    static class Unavailable extends RuntimeException {}
}
