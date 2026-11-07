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
        // 公車不在台北通範圍內（預設關閉）；這裡開啟只為了繼續測試公車程式本身，實際範圍見 ScopeTest
        registry.add("assistant.extensions", () -> "bus-arrivals");
    }

    @AfterAll
    static void stopFeed() {
        feedServer.stop(0);
    }

    @Test
    void listsSeededOfficialServices() throws Exception {
        mvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(52)) // 51 項台北通服務 + 測試開啟的公車
                .andExpect(jsonPath("$[*].category").value(hasItems(
                        "會員", "卡證", "帳務", "優惠", "市政服務", "訊息", "地圖與生活", "交通")))
                .andExpect(jsonPath("$[?(@.id == 'bus-arrivals')].mode").value(hasItems("LIVE")));
    }

    @Test
    void routesEveryGuideByItsCatalogAlias() throws Exception {
        for (ServiceEntry entry : catalog.listWithAliases()) {
            if (entry.mode.equals("GUIDE") && !entry.id.equals("road-report") && entry.inTaipeiPass) {
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
        assertGuide("附近的公廁在哪裡", "accessible-toilets");
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
    void extractsRouteFromEnglishQuestions() throws Exception {
        // 英文問句不用等 Gemma：規則式抽出路線並補問站牌
        for (String text : new String[] {"When will bus 1 arrive", "route 1 arrival time", "Is the 1 bus coming"}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + text + "\"}"))
                    .andExpect(jsonPath("$.context.route").value("1"))
                    .andExpect(jsonPath("$.type").value("CLARIFY"));
        }
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"When will the bus arrive\"}"))
                .andExpect(jsonPath("$.service.id").value("bus-arrivals"))
                .andExpect(jsonPath("$.type").value("CLARIFY"));
    }

    @Test
    void matchesEnglishKeywordsAsWholeWords() throws Exception {
        assertGuide("Where can I apply for a senior card", "senior-card");
        assertGuide("I lost my EasyCard", "easycard-link");
        assertGuide("How do I get a library card", "library-card");
        assertGuide("Where is the nearest public toilet", "accessible-toilets");
        assertGuide("How do I pay my water bill?", "water-payment");
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"passport application\"}"))
                .andExpect(jsonPath("$.type").value("OUT_OF_SCOPE"));
        // 英文陳情也要補問地點並保留描述，才能整理草稿
        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"I want to report a broken sidewalk\"}"))
                .andExpect(jsonPath("$.type").value("CLARIFY"))
                .andExpect(jsonPath("$.context.draft").value("I want to report a broken sidewalk"));
    }

    @Test
    void stopAliasesDropMetroPrefixAndParentheses() {
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("捷運公館站(羅斯福)", "捷運公館站", "公館站"),
                AssistantController.stopAliases("捷運公館站(羅斯福)"));
    }

    @Test
    void matchesOfficialEnglishStopNames() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("mrt ximen station", "mrt ximen", "ximen station", "ximen"),
                AssistantController.stopAliasesEn("MRT Ximen Sta."));
        for (String text : new String[] {"Taipei Main Station", "taipei train station", "Taipei Main Sta."}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"" + text + "\",\"context\":{\"route\":\"1\"}}"))
                    .andExpect(jsonPath("$.choices.length()").value(2))
                    .andExpect(jsonPath("$.choices[0].labelEn").value(
                            "Taipei Main Sta. (Zhongxiao), toward Songren Rd. (stop 10)"));
        }
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
                .andExpect(jsonPath("$.service.officialUrl").value("https://1999.gov.taipei/Front/main"));
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
                    {"BusInfo":[{"Id":1,"nameZh":"1","departureZh":"萬華","destinationZh":"松仁路",
                                 "departureEn":"Wanhua","destinationEn":"Songren Rd."}]}
                    """));
            server.createContext("/GetStop.gz", exchange -> writeGzip(exchange, """
                    {"BusInfo":[{"Id":10,"routeId":1,"nameZh":"測試站","goBack":"0","nameEn":"Taipei Main Sta. (Zhongxiao)"},
                                {"Id":11,"routeId":1,"nameZh":"測試站","goBack":"1","nameEn":"Taipei Main Sta. (Zhongxiao)"}]}
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
