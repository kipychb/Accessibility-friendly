package tw.townpass.assistant;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItems;

@SpringBootTest
@AutoConfigureMockMvc
class AssistantApiTest {
    private static final HttpServer feedServer = startFeed();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ServiceRepository catalog;

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
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(55))
                .andExpect(jsonPath("$[*].category").value(hasItems(
                        "會員", "卡證", "帳務", "優惠", "市政服務", "訊息", "地圖與生活", "交通")))
                .andExpect(jsonPath("$[?(@.id == 'bus-arrivals')].mode").value(hasItems("LIVE")));
    }

    @Test
    void routesEveryGuideByItsCatalogAlias() throws Exception {
        for (ServiceEntry entry : catalog.listWithAliases()) {
            if (entry.mode.equals("GUIDE") && !entry.id.equals("road-report")) {
                assertGuide("我想問" + entry.aliases.get(0), entry.id);
            }
        }
    }

    @Test
    void handlesSpokenAliasesAndSpecificTopicsBeforeGeneralAppHelp() throws Exception {
        assertGuide("臺北通的水費帳單怎麼查", "water-payment");
        assertGuide("臺北通停車費收據怎麼找", "payment-history");
        assertGuide("臺北通登入忘記密碼", "member-password");
        assertGuide("我要查 ＹｏｕＢｉｋｅ 還車位", "youbike");
        assertGuide("我要申辦台北通的新功能", "taipeipass-help");
        assertGuide("附近垃圾車在哪裡", "trash-routes");
    }

    private void assertGuide(String question, String id) throws Exception {
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("text", question))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GUIDANCE"))
                .andExpect(jsonPath("$.service.id").value(id))
                .andExpect(jsonPath("$.service.officialUrl").exists());
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
    void extractsRouteFromCommonSpokenForms() throws Exception {
        // 測試資料只有 1 號公車；這些口語說法都要抽出路線並補問站牌
        for (String text : new String[] {"1公車還有多久到", "1路公車多久來", "1號公車幾點來", "1路還有多久到"}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + text + "\"}"))
                    .andExpect(jsonPath("$.context.route").value("1"))
                    .andExpect(jsonPath("$.type").value("CLARIFY"));
        }
    }

    @Test
    void stopAliasesDropMetroPrefixAndParentheses() {
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("捷運公館站(羅斯福)", "捷運公館站", "公館站"),
                AssistantController.stopAliases("捷運公館站(羅斯福)"));
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
        // 補問方向時只說終點名稱（不加「往」）也要能回答
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"萬華\",\"context\":{\"route\":\"1\",\"stop\":\"測試站\"}}"))
                .andExpect(jsonPath("$.arrivals[0].status").value("NO_SERVICE"));
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
