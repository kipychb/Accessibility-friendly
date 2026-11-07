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
    // 英文路線：bus 307、route 307、bus no. 307、307 bus、red 30 bus（顏色轉成中文字首，與官方路線名一致）。
    // 英文問句原本只能靠 Gemma（預熱 2.5–5 分鐘），預熱前會直接回「找不到服務」。
    private static final Pattern ROUTE_EN = Pattern.compile(
            "(?i)\\b(?:bus|route)\\s*(?:no\\.?|number|#)?\\s*(?:(red|blue|brown|orange|green|small)\\s*)?([0-9]{1,4})\\b"
                    + "|\\b(?:(red|blue|brown|orange|green|small)\\s*)?([0-9]{1,4})\\s*bus\\b");
    private static final java.util.Map<String, String> ROUTE_COLORS = java.util.Map.of(
            "red", "紅", "blue", "藍", "brown", "棕", "orange", "橘", "green", "綠", "small", "小");
    private static final Pattern CATALOG_SEPARATORS = Pattern.compile("[\\s\\p{P}]+");
    private final ServiceRepository catalog;
    private final BusFeed bus;
    /** 英文關鍵字（aliases-en.json，開發階段人工撰寫）。與 services.json 分開，避免改變 Gemma 提示。 */
    private final java.util.Map<String, List<String>> englishAliases;

    /** 範圍：只回答台北通有的服務。這裡列出的 ID 即使不在台北通也會開放（預設空白，僅供測試或日後另行決定）。 */
    private final java.util.Set<String> extensions;

    AssistantController(ServiceRepository catalog, BusFeed bus,
                        com.fasterxml.jackson.databind.ObjectMapper mapper,
                        @org.springframework.beans.factory.annotation.Value("${assistant.extensions:}") String extensions)
            throws java.io.IOException {
        this.catalog = catalog;
        this.bus = bus;
        this.extensions = java.util.Arrays.stream(extensions.split(",")).map(String::strip)
                .filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        try (var input = new org.springframework.core.io.ClassPathResource("aliases-en.json").getInputStream()) {
            java.util.Map<String, Object> raw = mapper.readValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var aliases = new java.util.LinkedHashMap<String, List<String>>();
            raw.forEach((id, value) -> {
                if (value instanceof List<?> list) {
                    aliases.put(id, list.stream().map(item -> englishWords(String.valueOf(item))).toList());
                }
            });
            this.englishAliases = java.util.Collections.unmodifiableMap(aliases);
        }
    }

    @GetMapping("/services")
    List<ServiceEntry> services() {
        // 只提供台北通有的服務（Gemma 也只會從這份清單選）
        return catalog.listWithAliases().stream().filter(this::available).toList();
    }

    private boolean available(ServiceEntry entry) {
        return entry.inTaipeiPass || extensions.contains(entry.id);
    }

    /** 問到台北通沒有的服務：明確說明，不提供答案（前端會再附上 1999 撥號）。 */
    private static Reply notOffered(ServiceEntry entry) {
        return reply("OUT_OF_SCOPE", null, "台北通目前沒有提供「" + entry.title + "」這項服務，所以我無法回答。");
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
            return new Reply("GUIDANCE", "已幫你整理好陳情內容，但還沒有送出。下一步有兩種方式："
                            + "一、按「前往官方服務」開啟臺北市陳情系統，依網頁指示登入後，把下面的內容和地點填進表單送出；"
                            + "二、直接撥打 1999 市民熱線，用說的陳情。",
                    service("road-report"), null, null, null, null,
                    new Draft(context.draft(), request.text()));
        }
        var routeMatch = ROUTE.matcher(text);
        String spokenRoute = routeMatch.find() ? routeNumber(routeMatch.group(1)) : null;
        boolean englishBus = false;
        if (spokenRoute == null) {
            var en = ROUTE_EN.matcher(text);
            if (en.find()) {
                String color = en.group(1) != null ? en.group(1) : en.group(3);
                String digits = en.group(2) != null ? en.group(2) : en.group(4);
                spokenRoute = (color == null ? "" : ROUTE_COLORS.get(color.toLowerCase(java.util.Locale.ROOT))) + digits;
                englishBus = true;
            }
        }
        if (englishBus && context.route() == null) {
            return busReply(text, spokenRoute, context);
        }

        if (contains(text, "人行道", "陳情", "申訴", "道路破損", "1999")) {
            return complaint(text, request.text());
        }
        if (context.route() != null || spokenRoute != null && contains(text, "公車", "到站", "幾點來", "多久來", "多久到")) {
            return busReply(text, spokenRoute, context);
        }
        ServiceEntry match = matchService(text);
        if (match != null) {
            if (!available(match)) {
                return notOffered(match);
            }
            if ("road-report".equals(match.id)) {
                // 英文（或其他別名）說要陳情時，也走「補問地點 → 整理草稿」流程
                return complaint(text, request.text());
            }
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
                "目前找不到對應的台北通服務。你可以問帳號、卡證、繳費、優惠、市政服務或找地點。");
    }

    private Reply complaint(String text, String original) {
        ServiceEntry service = service("road-report");
        if (contains(text, "案件進度", "查進度", "案件查詢")
                || contains(text.toLowerCase(java.util.Locale.ROOT), "case status", "track my case", "check my case")) {
            return reply("GUIDANCE", service, "請前往臺北市陳情系統查詢案件進度。");
        }
        return new Reply("CLARIFY", "請說出道路名稱、門牌或附近地標，方便整理陳情草稿。",
                service, null, null, null, new Context(null, null, null, null, original), null);
    }

    private Reply busReply(String text, String spokenRoute, Context context) {
        ServiceEntry service = service("bus-arrivals");
        if (!available(service)) {
            return notOffered(service); // 台北通沒有公車到站功能
        }
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
            if (matchedName == null) {
                // 英文站名（官方 nameEn，例：Taipei Main Sta. (Zhongxiao)）；整字比對，最長者優先
                String padded = " " + englishStopText(text) + " ";
                matchedName = routeStops.stream()
                        .filter(stop -> stop.nameEn() != null && !stop.nameEn().isBlank())
                        .map(stop -> java.util.Map.entry(normalize(stop.name()), stopAliasesEn(stop.nameEn()).stream()
                                .filter(alias -> padded.contains(" " + alias + " "))
                                .mapToInt(String::length).max().orElse(0)))
                        .filter(entry -> entry.getValue() > 0)
                        .max(java.util.Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                                .thenComparingInt(entry -> -entry.getKey().length()))
                        .map(java.util.Map.Entry::getKey).orElse(null);
            }
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
                    String destEn = stop.direction() == 0 ? route.destinationEn() : route.departureEn();
                    String labelEn = blankToNull(stop.nameEn()) == null || blankToNull(destEn) == null ? null
                            : stop.nameEn() + ", toward " + destEn + " (stop " + stop.id() + ")";
                    return new Choice(stop.name() + "，往" + destination(route, stop) + "（站牌 " + stop.id() + "）",
                            labelEn, new Context(routeName, stop.name(), stop.direction(), stop.id(), null));
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

    /** 英文站名別名：小寫、Sta.→station、Rd.→road；可省略括號說明、MRT 字首與結尾 station。 */
    static List<String> stopAliasesEn(String nameEn) {
        String base = englishStopText(nameEn.replaceAll("\\([^)]*\\)", " "));
        String noMrt = base.startsWith("mrt ") ? base.substring(4) : base;
        var aliases = new java.util.LinkedHashSet<String>();
        for (String alias : List.of(englishStopText(nameEn), base, noMrt)) {
            aliases.add(alias);
            if (alias.endsWith(" station")) {
                aliases.add(alias.substring(0, alias.length() - " station".length()));
            }
        }
        return aliases.stream().filter(alias -> alias.length() >= 4).toList();
    }

    /** 英文站名正規化；口語「Taipei train/railway station」視同官方的 Taipei Main Station。 */
    private static String englishStopText(String text) {
        String words = englishWords(text.replaceAll("(?i)\\bsta\\.", "station").replaceAll("(?i)\\brd\\.", "road")
                .replaceAll("(?i)\\bst\\.", "street"));
        return words.replaceAll("\\bsta\\b", "station")
                .replaceAll("\\btaipei (?:train|railway) station\\b", "taipei main station");
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
        // 比對完整清單（含台北通沒有的服務），才能明確回答「台北通沒有提供這項服務」
        for (ServiceEntry entry : catalog.listWithAliases()) {
            for (String alias : entry.aliases) {
                String candidate = catalogText(alias);
                if (candidate.length() > length && query.contains(candidate)) {
                    best = entry;
                    length = candidate.length();
                }
            }
        }
        return best != null ? best : matchEnglish(text);
    }

    /** 英文整字比對（不分大小寫）；最長的關鍵字優先，避免 tax 誤中 taxi 這類情況。 */
    private ServiceEntry matchEnglish(String text) {
        String query = englishWords(text);
        if (query.isBlank()) {
            return null;
        }
        String padded = " " + query + " ";
        String bestId = null;
        int length = 0;
        for (var entry : englishAliases.entrySet()) {
            for (String alias : entry.getValue()) {
                if (alias.length() > length && padded.contains(" " + alias + " ")) {
                    bestId = entry.getKey();
                    length = alias.length();
                }
            }
        }
        return bestId == null ? null : catalog.findById(bestId).orElse(null);
    }

    private static String englishWords(String text) {
        return normalize(text).toLowerCase(java.util.Locale.ROOT).replace("'", "")
                .replaceAll("[^a-z0-9]+", " ").strip();
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Choice(String label, String labelEn, Context context) {}
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
