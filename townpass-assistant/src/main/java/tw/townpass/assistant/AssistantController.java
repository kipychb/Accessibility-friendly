package tw.townpass.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
class AssistantController {
    // 路線後接「號」「路」「公車」皆可（例：307號、307路、307公車、紅30公車）。
    // 原本只接受「號」「路公車」，口語常說的「307公車還有多久到」會抽不到路線。
    private static final Pattern ROUTE = Pattern.compile(
            "((?:紅|藍|棕|橘|綠|小|市民小巴|內科通勤)?[0-9]{1,4}(?:區間車|區間|副線)?|[零〇一二兩三四五六七八九十百]{1,5})\\s*(?:號|路|公車)");
    private static final Pattern CATALOG_SEPARATORS = Pattern.compile("[\\s\\p{P}]+");
    private final ServiceRepository catalog;
    private final BusFeed bus;

    AssistantController(ServiceRepository catalog, BusFeed bus) {
        this.catalog = catalog;
        this.bus = bus;
    }

    @GetMapping("/services")
    List<ServiceEntry> services() {
        return catalog.listWithAliases();
    }

    @PostMapping("/query")
    Reply query(@RequestBody Query request) {
        if (request == null || request.text() == null || request.text().isBlank()
                || request.text().length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be 1–400 characters");
        }
        Context context = request.context() == null ? new Context(null, null, null, null, null) : request.context();
        if (tooLong(context.route(), 40) || tooLong(context.stop(), 80) || tooLong(context.draft(), 400)
                || context.direction() != null && context.direction() != 0 && context.direction() != 1
                || context.stopId() != null && context.stopId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid context");
        }
        String text = normalize(request.text());
        if (context.draft() != null && !context.draft().isBlank()) {
            return new Reply("GUIDANCE", "已整理陳情草稿。請確認描述與地點，再前往臺北市陳情系統填寫及送出。",
                    service("road-report"), null, null, null, null,
                    new Draft(context.draft(), request.text()));
        }
        var routeMatch = ROUTE.matcher(text);
        String spokenRoute = routeMatch.find() ? routeNumber(routeMatch.group(1)) : null;

        if (contains(text, "人行道", "陳情", "申訴", "道路破損", "1999")) {
            ServiceEntry service = service("road-report");
            if (contains(text, "案件進度", "查進度", "案件查詢")) {
                return reply("GUIDANCE", service, "請前往臺北市陳情系統查詢案件進度。");
            }
            return new Reply("CLARIFY", "請說出道路名稱、門牌或附近地標，方便整理陳情草稿。",
                    service, null, null, null, new Context(null, null, null, null, request.text()), null);
        }
        if (context.route() != null || spokenRoute != null && contains(text, "公車", "到站", "幾點來", "多久來", "多久到")) {
            return busReply(text, spokenRoute, context);
        }
        ServiceEntry match = matchService(text);
        if (match != null) {
            return "bus-arrivals".equals(match.id) ? busReply(text, spokenRoute, context)
                    : reply("GUIDANCE", match, match.summary);
        }
        if (text.contains("無障礙設施")) {
            return reply("GUIDANCE", service("accessible-toilets"),
                    "如果你在找無障礙廁所，可以按行政區查看市府公廁清單。若你想找其他設施，請說出設施類型。");
        }
        if (text.contains("公車")) {
            return busReply(text, spokenRoute, context);
        }
        if (contains(catalogText(text), "台北通", "taipeipass")) {
            ServiceEntry help = service("taipeipass-help");
            return reply("GUIDANCE", help, "目前找不到這個台北通功能的對應資料。你可以查看官方功能教學與常見問題。");
        }
        return reply("OUT_OF_SCOPE", null,
                "目前找不到對應的台北通服務。你可以問帳號、卡證、市政申辦、繳費、優惠、交通或找地點。");
    }

    private Reply busReply(String text, String spokenRoute, Context context) {
        ServiceEntry service = service("bus-arrivals");
        String requestedRoute = spokenRoute == null ? context.route() : spokenRoute;
        if (requestedRoute != null && requestedRoute.isBlank()) {
            requestedRoute = null;
        }
        if (requestedRoute == null && text.matches("(?:紅|藍|棕|橘|綠|小|市民小巴|內科通勤)?[0-9]{1,4}")) {
            requestedRoute = text;
        }
        if (requestedRoute == null) {
            return new Reply("CLARIFY", "請說出公車路線與站牌，例如一號公車華江站幾點來。",
                    service, null, null, null, new Context("", null, null, null, null), null);
        }
        String routeName = normalize(requestedRoute);
        boolean sameRoute = context.route() != null && routeName.equals(normalize(context.route()));
        try {
            BusFeed.Topology topology = bus.topology();
            var routes = topology.routes().stream()
                    .filter(route -> normalize(route.name()).equals(routeName)).toList();
            if (routes.isEmpty()) {
                return reply("NO_RESULT", service, "官方資料找不到這條公車路線。請確認路線名稱。");
            }
            var routeIds = routes.stream().map(BusFeed.Route::id).collect(java.util.stream.Collectors.toSet());
            var routeStops = topology.stops().stream().filter(stop -> routeIds.contains(stop.routeId())).toList();
            String stopText = text;
            for (BusFeed.Route route : routes) {
                for (String endpoint : List.of(route.departure(), route.destination())) {
                    if (!endpoint.isBlank()) {
                        stopText = stopText.replace("往" + normalize(endpoint), "");
                    }
                }
            }
            String stopQuery = stopText;
            // 官方站名常帶「捷運」字首或括號說明（例：捷運公館站(羅斯福)），口語只說「公館站」，
            // 所以也比對去掉這些部分的別名；以符合的別名長度優先，避免短站名誤中。
            String matchedName = routeStops.stream().map(BusFeed.Stop::name).map(AssistantController::normalize)
                    .distinct()
                    .map(name -> java.util.Map.entry(name, stopAliases(name).stream()
                            .filter(alias -> alias.length() >= 2 && stopQuery.contains(alias))
                            .mapToInt(String::length).max().orElse(0)))
                    .filter(entry -> entry.getValue() > 0)
                    .max(java.util.Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                            .thenComparingInt(entry -> -entry.getKey().length()))
                    .map(java.util.Map.Entry::getKey).orElse(null);
            if (matchedName == null && sameRoute && context.stop() != null) {
                matchedName = normalize(context.stop());
            }
            if (matchedName == null) {
                // 舉出這條路線實際停靠的站名，讓使用者知道是站名不在路線上，而不是沒聽懂
                List<String> examples = routeStops.stream().map(BusFeed.Stop::name).distinct()
                        .filter(name -> name.startsWith("捷運")).limit(2).toList();
                String hint = examples.isEmpty() ? "" : "，例如" + String.join("、", examples);
                return new Reply("CLARIFY", "請說出 " + routeName + " 公車有停靠的站牌名稱" + hint + "。",
                        service, null, null, null, new Context(routeName, null, null, null, null), null);
            }
            String stopName = matchedName;
            boolean sameStop = sameRoute && context.stop() != null && stopName.equals(normalize(context.stop()));
            // 已在補問方向（同路線、同站牌）時，接受只說終點名稱
            Integer direction = direction(text, routes, sameStop);
            if (direction == null && sameStop) {
                direction = context.direction();
            }
            Integer requestedDirection = direction;
            var stops = routeStops.stream().filter(stop -> normalize(stop.name()).equals(stopName))
                    .filter(stop -> requestedDirection == null || stop.direction() == requestedDirection)
                    .filter(stop -> !sameStop || context.stopId() == null || stop.id() == context.stopId())
                    .distinct().toList();
            if (stops.isEmpty()) {
                return new Reply("NO_RESULT", "找不到符合的站牌或方向，請確認後再試。",
                        service, null, null, null, new Context(routeName, null, null, null, null), null);
            }
            if (stops.size() > 1) {
                List<Choice> choices = stops.stream().limit(12).map(stop -> {
                    BusFeed.Route route = routeFor(routes, stop);
                    return new Choice(stop.name() + "，往" + destination(route, stop) + "（站牌 " + stop.id() + "）",
                            new Context(routeName, stop.name(), stop.direction(), stop.id(), null));
                }).toList();
                return new Reply("CLARIFY", "有多個站牌或方向。請說出要往哪裡，或從下方選擇。",
                        service, null, null, choices, new Context(routeName, stopName, null, null, null), null);
            }
            BusFeed.Stop stop = stops.get(0);
            BusFeed.Route route = routeFor(routes, stop);
            BusFeed.Snapshot snapshot = bus.arrivals();
            Integer seconds = snapshot.estimates().get(new BusFeed.StopKey(stop.routeId(), stop.id()));
            String prefix = route.name() + " 公車，" + stop.name() + "，往" + destination(route, stop) + "，";
            Arrival arrival = arrival(route, stop, seconds);
            String message = switch (arrival.status()) {
                case "DUE" -> "即將到站。";
                case "ESTIMATED" -> "預估約 " + ((seconds + 59) / 60) + " 分鐘到站。";
                case "NOT_DEPARTED" -> "尚未發車。";
                case "SKIPPED" -> "因交通管制暫不停靠。";
                case "LAST_PASSED" -> "末班車已過。";
                case "NO_SERVICE" -> "今日未營運。";
                default -> "目前沒有可用的到站預估。";
            };
            return new Reply("ANSWER", prefix + message, service, snapshot.updatedAt(), List.of(arrival),
                    null, null, null);
        } catch (BusFeed.Unavailable ex) {
            return reply("UPSTREAM_UNAVAILABLE", service, "目前無法取得新鮮的公車到站資料。可前往大臺北公車查詢。");
        }
    }

    private static Arrival arrival(BusFeed.Route route, BusFeed.Stop stop, Integer seconds) {
        String status = seconds == null ? "NO_ESTIMATE" : switch (seconds) {
            case 0 -> "DUE";
            case -1 -> "NOT_DEPARTED";
            case -2 -> "SKIPPED";
            case -3 -> "LAST_PASSED";
            case -4 -> "NO_SERVICE";
            default -> seconds > 0 ? "ESTIMATED" : "NO_ESTIMATE";
        };
        String destinationEn = stop.direction() == 0 ? route.destinationEn() : route.departureEn();
        return new Arrival(route.id(), route.name(), stop.id(), stop.name(), stop.direction(),
                destination(route, stop), status, seconds != null && seconds >= 0 ? seconds : null,
                blankToNull(stop.nameEn()), blankToNull(destinationEn));
    }

    /** 站名與口語別名：原名、去括號、去「捷運」字首，以及兩者都去掉的版本。 */
    static List<String> stopAliases(String name) {
        String noParen = name.replaceAll("[(（][^)）]*[)）]", "");
        String noMetro = noParen.startsWith("捷運") ? noParen.substring(2) : noParen;
        return List.of(name, noParen, noMetro).stream().distinct().toList();
    }

    /**
     * 判斷方向。一般句子要「往＋終點」才算；補問方向時（[answeringDirection]）
     * 使用者常只說終點名稱（例：「撫遠街」），也接受不加「往」。
     */
    private static Integer direction(String text, List<BusFeed.Route> routes, boolean answeringDirection) {
        String prefix = answeringDirection ? "" : "往";
        boolean outbound = text.contains("去程") || routes.stream().anyMatch(route ->
                !route.destination().isBlank() && text.contains(prefix + normalize(route.destination())));
        boolean inbound = text.contains("返程") || routes.stream().anyMatch(route ->
                !route.departure().isBlank() && text.contains(prefix + normalize(route.departure())));
        return outbound == inbound ? null : outbound ? 0 : 1;
    }

    private static BusFeed.Route routeFor(List<BusFeed.Route> routes, BusFeed.Stop stop) {
        return routes.stream().filter(route -> route.id() == stop.routeId()).findFirst().orElseThrow();
    }

    private static String destination(BusFeed.Route route, BusFeed.Stop stop) {
        return stop.direction() == 0 ? route.destination() : route.departure();
    }

    private ServiceEntry matchService(String text) {
        String query = catalogText(text);
        ServiceEntry best = null;
        int length = 0;
        for (ServiceEntry entry : services()) {
            for (String alias : entry.aliases) {
                String candidate = catalogText(alias);
                if (candidate.length() > length && query.contains(candidate)) {
                    best = entry;
                    length = candidate.length();
                }
            }
        }
        return best;
    }

    private static String catalogText(String text) {
        return CATALOG_SEPARATORS.matcher(normalize(text).toLowerCase(java.util.Locale.ROOT)).replaceAll("");
    }

    private ServiceEntry service(String id) {
        return catalog.findById(id).orElseThrow();
    }

    private static Reply reply(String type, ServiceEntry service, String speech) {
        return new Reply(type, speech, service, null, null, null, null, null);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replace('臺', '台');
    }

    private static boolean contains(String text, String... terms) {
        return java.util.Arrays.stream(terms).anyMatch(text::contains);
    }

    private static boolean tooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private static String routeNumber(String value) {
        String digits = "零一二三四五六七八九";
        if (!value.matches("[零〇一二兩三四五六七八九十百]+")) {
            return value;
        }
        int total = 0;
        int pending = 0;
        for (char symbol : value.replace('兩', '二').replace('〇', '零').toCharArray()) {
            if (symbol == '十' || symbol == '百') {
                total += (pending == 0 ? 1 : pending) * (symbol == '十' ? 10 : 100);
                pending = 0;
            } else {
                pending = pending * 10 + digits.indexOf(symbol);
            }
        }
        return Integer.toString(total + pending);
    }

    record Query(String text, Context context) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Context(String route, String stop, Integer direction, Long stopId, String draft) {}
    record Choice(String label, Context context) {}
    record Draft(String description, String location) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Arrival(long routeId, String route, long stopId, String stop, int direction,
                   String destination, String status, Integer estimateSeconds,
                   String stopEn, String destinationEn) {}

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Reply(String type, String speech, ServiceEntry service, Instant updatedAt,
                 List<Arrival> arrivals, List<Choice> choices, Context context, Draft draft) {}
}
