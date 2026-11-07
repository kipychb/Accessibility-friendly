package tw.townpass.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class BusFeedTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC);
    private final BusFeed feed = spy(new BusFeed(clock, mapper, "http://127.0.0.1/"));

    @Test
    void rejectsStaleData() throws Exception {
        doReturn(mapper.readTree("""
                {"EssentialInfo":{"UpdateTime":"2026/11/07 13:56:59"},
                 "BusInfo":[{"RouteID":1,"StopID":2,"EstimateTime":"60"}]}
                """)).when(feed).fetch("EstimateTime");
        assertThrows(BusFeed.Unavailable.class, feed::arrivals);
    }

    @Test
    void rejectsMissingUpstream() {
        doThrow(new BusFeed.Unavailable()).when(feed).fetch("EstimateTime");
        assertThrows(BusFeed.Unavailable.class, feed::arrivals);
    }

    @Test
    void keepsNearestValidBusForTheSameStop() throws Exception {
        doReturn(mapper.readTree("""
                {"EssentialInfo":{"UpdateTime":"2026/11/07 13:59:59"},
                 "BusInfo":[{"RouteID":1,"StopID":2,"EstimateTime":"360"},
                            {"RouteID":1,"StopID":2,"EstimateTime":"120"},
                            {"RouteID":1,"StopID":2,"EstimateTime":"-1"}]}
                """)).when(feed).fetch("EstimateTime");
        assertEquals(120, feed.arrivals().estimates().get(new BusFeed.StopKey(1, 2)));
    }
}
