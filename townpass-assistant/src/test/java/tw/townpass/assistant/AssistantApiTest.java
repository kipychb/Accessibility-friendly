package tw.townpass.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssistantApiTest {
    private static final HttpServer feedServer = startFeed();
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("assistant.bus.base-url", () -> "http://127.0.0.1:" + feedServer.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stopFeed() {
        feedServer.stop(0);
    }

    @Test
    void listsSeededOfficialServices() throws Exception {
        mvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[2].mode").value("LIVE"));
    }

    @Test
    void decodesOfficialGzipAndJoinsRouteStopAndEstimate() throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"text":"請問一號公車測試站往松仁路幾點來"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ANSWER"))
                .andExpect(jsonPath("$.arrivals[0].stopId").value(10))
                .andExpect(jsonPath("$.arrivals[0].estimateSeconds").value(121))
                .andExpect(jsonPath("$.speech").value("1 公車，測試站，往松仁路，預估約 3 分鐘到站。"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void asksForStopAndDisambiguatesDirection() throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"我想問１號公車幾點來\"}"))
                .andExpect(jsonPath("$.context.route").value("1"))
                .andExpect(jsonPath("$.type").value("CLARIFY"));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"測試站\",\"context\":{\"route\":\"1\"}}"))
                .andExpect(jsonPath("$.choices.length()").value(2));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"往萬華\",\"context\":{\"route\":\"1\",\"stop\":\"測試站\"}}"))
                .andExpect(jsonPath("$.arrivals[0].status").value("NO_SERVICE"))
                .andExpect(jsonPath("$.arrivals[0].estimateSeconds").doesNotExist());
    }

    @Test
    void complaintCollectsLocationWithoutSubmitting() throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"人行道破損，輪椅很難通過\"}"))
                .andExpect(jsonPath("$.type").value("CLARIFY"))
                .andExpect(jsonPath("$.context.draft").value("人行道破損，輪椅很難通過"));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"民生東路三段1號\",\"context\":{\"draft\":\"人行道破損\"}}"))
                .andExpect(jsonPath("$.type").value("GUIDANCE"))
                .andExpect(jsonPath("$.draft.location").value("民生東路三段1號"))
                .andExpect(jsonPath("$.service.officialUrl").value("https://hello.gov.taipei/Front/main"));
    }

    @Test
    void handlesGuidesOutOfScopeAndBadInput() throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"我要申請敬老卡\"}"))
                .andExpect(jsonPath("$.service.id").value("senior-card"));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"附近有無障礙設施嗎\"}"))
                .andExpect(jsonPath("$.type").value("GUIDANCE"))
                .andExpect(jsonPath("$.service.id").value("accessible-toilets"));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"明天天氣如何\"}"))
                .andExpect(jsonPath("$.type").value("OUT_OF_SCOPE"));
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    private static HttpServer startFeed() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/GetRoute.gz", exchange -> writeGzip(exchange, """
                    {"BusInfo":[{"Id":1,"nameZh":"1","departureZh":"萬華","destinationZh":"松仁路"}]}
                    """));
            server.createContext("/GetStop.gz", exchange -> writeGzip(exchange, """
                    {"BusInfo":[{"Id":10,"routeId":1,"nameZh":"測試站","goBack":"0"},
                                {"Id":11,"routeId":1,"nameZh":"測試站","goBack":"1"}]}
                    """));
            server.createContext("/GetEstimateTime.gz", exchange -> writeGzip(exchange, """
                    {"EssentialInfo":{"UpdateTime":"%s"},"BusInfo":[
                      {"RouteID":1,"StopID":10,"EstimateTime":"121","GoBack":"0"},
                      {"RouteID":1,"StopID":11,"EstimateTime":"-4","GoBack":"2"}]}
                    """.formatted(LocalDateTime.now(ZoneId.of("Asia/Taipei"))
                    .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")))));
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeGzip(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        var data = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(data)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = data.toByteArray();
        exchange.sendResponseHeaders(200, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }
}
