package tw.townpass.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 預設設定：只回答台北通有的服務。台北通沒有的服務（例如公車到站）一律說明「台北通沒有提供」。 */
@SpringBootTest
@AutoConfigureMockMvc
class ScopeTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void listsOnlyTaipeiPassServices() throws Exception {
        mvc.perform(get("/api/v1/services"))
                .andExpect(jsonPath("$.length()").value(51))
                .andExpect(jsonPath("$[*].id").value(not(hasItem("bus-arrivals"))))
                .andExpect(jsonPath("$[*].id").value(not(hasItem("accessible-taxi"))));
    }

    @Test
    void refusesServicesTaipeiPassDoesNotOffer() throws Exception {
        for (String text : new String[] {"307公車還有多久到", "When will bus 307 arrive", "我要叫無障礙計程車",
                "道路施工在哪裡", "公車幾點來"}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(Map.of("text", text))))
                    .andExpect(jsonPath("$.type").value("OUT_OF_SCOPE"))
                    .andExpect(jsonPath("$.speech").value(startsWith("台北通目前沒有提供")))
                    .andExpect(jsonPath("$.service").doesNotExist());
        }
    }

    @Test
    void refusesUnrelatedQuestionsWithoutInventingAnswers() throws Exception {
        for (String text : new String[] {"晚餐吃什麼", "今天天氣如何", "講個笑話", "What should I eat for dinner"}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(Map.of("text", text))))
                    .andExpect(jsonPath("$.type").value("OUT_OF_SCOPE"))
                    .andExpect(jsonPath("$.service").doesNotExist());
        }
    }

    @Test
    void stillAnswersTaipeiPassServices() throws Exception {
        for (var c : Map.of("我要申請敬老卡", "senior-card", "附近的公廁在哪", "accessible-toilets",
                "我要陳情", "road-report", "Where are my coupons", "coupons").entrySet()) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsBytes(Map.of("text", c.getKey()))))
                    .andExpect(jsonPath("$.service.id").value(c.getValue()));
        }
    }
}
