import simpledisplays.XmlDisplayService;
import simpledisplays.api.*;
import net.kyori.adventure.text.TextComponent;
import java.nio.file.*;
import java.util.*;

/** Runs against the actual SimpleDisplays implementation, with the production draw budget. */
public class DisplayRefreshTemplateProbe {
    static Map<String,Object> data(boolean inService) {
        var metadata = Map.of("name", Map.of("zh", "测试线", "en", "Test Line"),
                "badge", Map.of("zh", "2", "en", "2"), "themeColor", "#125a69");
        var lines = Map.of("line1", Map.of("id", "line1", "color", "#2574ba", "metadata", metadata),
                "line2", Map.of("id", "line2", "color", "#a52b3b", "metadata", metadata));
        var stops = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < 15; i++) {
            stops.add(Map.of("stationId", "s" + i, "stationName", "测试车站" + i,
                    "metadata", Map.of("name", Map.of("zh", "测试车站" + i, "en", "Central Station " + i), "transfers", List.of("line2")),
                    "serviceId", "service", "runId", "run", "callId", "call-" + i, "status", i == 4 ? "PASS" : "STOP"));
        }
        var op = Map.of("currentRun", Map.of("lineId", "line1", "isInService", inService,
                        "metadata", Map.of("class", Map.of("zh", "快速", "en", "Express"))),
                "values", Map.of("destination", Map.of("zh", "中央车站", "en", "Central Station")),
                "stops", stops, "stationIndex", 3, "phase", "in_transit", "nextStation", stops.get(3), "unresolvedRuns", List.of());
        return Map.of("operational", op, "lines", lines, "display", Map.of("openingSide", "this"));
    }
    static Map<String,TextDraw> frame(DisplayTemplate template, boolean inService, long tick) {
        var result = new LinkedHashMap<String,TextDraw>();
        for (var draw : template.draw(new DisplayContext(data(inService), null, Map.of(), tick, null))) {
            if (result.put(draw.getKey(), draw) != null) throw new AssertionError("duplicate key " + draw.getKey());
            if (draw.getZIndex() < 0 || draw.getZIndex() > 63) throw new AssertionError("invalid depth");
            if (draw.getZIndex() == 30 && !Objects.equals("minecraft:default", String.valueOf(draw.getText().font())))
                throw new AssertionError("ordinary label font " + draw.getKey() + ": " + draw.getText().font());
        }
        return result;
    }
    static String text(TextDraw draw) {
        if (draw == null) throw new AssertionError("missing label");
        return ((TextComponent) draw.getText()).content();
    }
    static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    static void interior(DisplayTemplate template) {
        for (long tick : new long[]{0, 170, 180, 350, 360, 530, 540, 710}) {
            var normal = frame(template, true, tick);
            equal(tick / 180 % 2 == 0 ? "下一站" : "Next station", text(normal.get("id:station-caption")));
            if (normal.size() < 30) throw new AssertionError("missing normal cabin content");
            if (normal.values().stream().noneMatch(d -> Objects.equals("javarailways:display_geometry", String.valueOf(d.getText().font()))))
                throw new AssertionError("geometry font missing");
            equal(0, frame(template, false, tick).size());
            equal(normal, frame(template, true, tick)); // Returning from EMPTY regenerates identical content.
        }
        var on = frame(template, true, 0);
        var off = frame(template, true, 10);
        equal(on.keySet(), off.keySet());
        int blinkChanges = 0;
        for (var key : on.keySet()) {
            var a = on.get(key); var b = off.get(key);
            equal(a.getZIndex(), b.getZIndex());
            if (key.endsWith("/dot") && !a.getText().equals(b.getText())) {
                blinkChanges++;
                equal("", text(b)); equal(a.getX(), b.getX()); equal(a.getY(), b.getY());
            }
        }
        equal(1, blinkChanges);
        System.out.println("cabin: 180-tick language boundaries; default labels; geometry font retained; out-of-service EMPTY; stable blink keys/depth PASS");
    }
    static void exterior(DisplayTemplate template, String file) {
        for (long tick : new long[]{0, 119, 120, 239, 240, 359, 360}) {
            var normal = frame(template, true, tick);
            boolean zh = tick / 120 % 2 == 0;
            equal(zh ? "中央车站" : "Central Station", text(normal.get("id:destination")));
            equal(zh ? "快速" : "Express", text(normal.get("id:class")));
            var emptyService = frame(template, false, tick);
            equal(zh ? "回送" : "Out of Service", text(emptyService.get("id:destination")));
            equal(Set.of("id:background", "id:destination"), emptyService.keySet());
        }
        System.out.println(file + ": 120-tick language boundaries; normal service retained; bilingual out-of-service without class badge PASS");
    }
    public static void main(String[] files) throws Exception {
        try (var service = new XmlDisplayService()) {
            // Compile and draw one small script before the real templates to initialize GraalJS.
            try (var warm = service.compile("<screen width=\"10\" height=\"10\"><div id=\"root\"/><script>function render(ctx) { const n=document.createElement('span'); n.id='warm'; n.textContent='warm'; document.getElementById('root').appendChild(n); }</script></screen>", Map.of())) {
                for (int i=0; i<40; i++) warm.draw(new DisplayContext(Map.of(), null, Map.of(), i, null));
            }
            for (String file : files) try (var template = service.compile(Files.readString(Path.of(file)), Map.of())) {
                if (file.contains("cabin-route")) interior(template); else exterior(template, Path.of(file).getFileName().toString());
            }
        }
    }
}
