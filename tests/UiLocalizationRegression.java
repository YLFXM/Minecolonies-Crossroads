import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R10-fix3: every panel string is translated, not written in code.
 *
 * <p>Two classes of bug this locks down. First, a static label left as English
 * in a gui xml: BlockUI shows it verbatim, so a Chinese client reads "Release
 * Plans" next to translated buttons, and a rename misses it entirely because
 * nothing looks at the attribute. Second, text composed in Java from a literal:
 * the wand printed "Plan: BUILT" and the surveyor "BUILT | len 12", which also
 * froze the {@link RoadState} enum name into the screen.</p>
 *
 * <p>The work-order list is the harder case and is asserted separately: its rows
 * crossed the network as pre-rendered text, so the server's language decided what
 * the client saw. Rows must now carry a serialized component plus enum names, and
 * no server-side literal may sneak back in.</p>
 */
public final class UiLocalizationRegression {
    private static final Path GUI = Paths.get("src/main/resources/assets/rp_crossroads/gui");
    private static final Path LANG = Paths.get("src/main/resources/assets/rp_crossroads/lang");
    private static final Path CLIENT = Paths.get("src/main/java/com/YLFXM/rp/crossroads/client");
    private static final Path ROAD = Paths.get("src/main/java/com/YLFXM/rp/crossroads/road");

    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(final String[] args) throws Exception {
        final JsonObject zh = json("zh_cn.json");
        final JsonObject en = json("en_us.json");

        // Both files must describe the same key set, or one language silently falls back.
        final Set<String> zhKeys = new TreeSet<>(zh.keySet());
        final Set<String> enKeys = new TreeSet<>(en.keySet());
        check(zhKeys.equals(enKeys), "zh_cn and en_us declare the same keys, differing only in: "
                + difference(zhKeys, enKeys));

        guiLabels(zh, en);
        states(zh, en);
        noLiteralPanelText();
        workOrderRows(zh, en);

        System.out.println("UiLocalizationRegression: " + checks + " checks passed");
    }

    /** Every gui xml label is a key, a symbol, or a number; every key exists in both files. */
    private static void guiLabels(final JsonObject zh, final JsonObject en) throws Exception {
        int inspected = 0;
        try (var files = Files.list(GUI)) {
            for (final Path file : files.toList()) {
                if (!file.toString().endsWith(".xml")) continue;
                final String text = Files.readString(file);
                final Matcher label = Pattern.compile("label=\"([^\"]*)\"").matcher(text);
                while (label.find()) {
                    final String value = label.group(1);
                    inspected++;
                    if (value.matches("[-+\\[\\]x \\d]*")) continue;
                    check(value.startsWith("$("), file.getFileName() + " label is translated: " + value);
                }
                final Matcher key = Pattern.compile("\\$\\(([^)]+)\\)").matcher(text);
                while (key.find()) {
                    final String id = key.group(1);
                    check(!id.startsWith("mcreconstruction."), "no stale key prefix: " + id);
                    // Every label key must be ours: a key owned by another mod
                    // turns into "MISSING: gui.cancel" the day that mod renames it.
                    check(id.startsWith("rp_crossroads."), "gui label key belongs to this mod: " + id);
                    check(zh.has(id), "key exists in zh_cn: " + id);
                    check(en.has(id), "key exists in en_us: " + id);
                }
            }
        }
        check(inspected >= 20, "gui labels were inspected: " + inspected);
    }

    /** Every {@link RoadState} has a display key; the enum name never reaches a panel. */
    private static void states(final JsonObject zh, final JsonObject en) throws Exception {
        final Class<?> state = Class.forName("com.YLFXM.rp.crossroads.road.RoadState");
        final Object[] constants = state.getEnumConstants();
        check(constants.length > 0, "RoadState has constants");
        for (final Object constant : constants) {
            final String key = "rp_crossroads.state." + ((Enum<?>) constant).name().toLowerCase(java.util.Locale.ROOT);
            check(zh.has(key), "state key exists in zh_cn: " + key);
            check(en.has(key), "state key exists in en_us: " + key);
        }
        final String source = Files.readString(ROAD.resolve("RoadState.java"));
        check(source.contains("public net.minecraft.network.chat.Component display()"),
                "RoadState renders through a component");
    }

    /** Panel classes must not build player text from literals. */
    private static void noLiteralPanelText() throws Exception {
        // Each entry: file, literal that used to be hardcoded, why it must stay gone.
        final String[][] gone = {
                {"WindowRoadWand.java", "\"Mirror: \"", "mirror button"},
                {"WindowRoadWand.java", "\"No plan\"", "wand state line"},
                {"WindowRoadWand.java", "\"Bound: \"", "wand binding line"},
                {"WindowRoadWand.java", "\"Unbound: right-click", "wand binding line"},
                {"WindowRoadSurveyor.java", "\"Road Surveyor\"", "surveyor title"},
                {"WindowRoadSurveyor.java", "\"Selected: \"", "surveyor selection line"},
                {"WindowRoadSurveyor.java", "\" | len \"", "surveyor row"},
                {"WindowRoadSurveyor.java", "\"Select All\"", "surveyor button"},
                {"WindowRoadSurveyor.java", "\"Release Plans\"", "surveyor button"},
                {"WindowWorkOrderList.java", "\" order(s) | selected \"", "work order summary"},
                {"WindowWorkOrderList.java", "\"Work Order Scroll\"", "work order title"},
                {"WindowWorkOrderList.java", "\"priority \"", "work order row"},
                {"WindowWorkOrderList.java", "\"Cancel Selected Orders\"", "work order button"},
                {"WindowRoadController.java", "\"Edit Blueprint: \"", "controller edit button"},
                {"WindowRoadController.java", "\"repair in progress", "controller detail line"},
                {"WindowRoadController.java", "plan.state().toString()", "controller state line"},
        };
        for (final String[] entry : gone) {
            final Path file = CLIENT.resolve(entry[0]);
            check(Files.exists(file), "panel class still exists: " + entry[0]);
            check(!Files.readString(file).contains(entry[1]),
                    entry[1] + " is translated, not hardcoded (" + entry[2] + ")");
        }
    }

    /**
     * Work-order rows cross the network as data, not as rendered text.
     *
     * <p>A pre-rendered status would be built with the server's language; a
     * pre-rendered name loses MineColonies' own translations for the same reason.</p>
     */
    private static void workOrderRows(final JsonObject zh, final JsonObject en) throws Exception {
        final String entry = Files.readString(
                Paths.get("src/main/java/com/YLFXM/rp/crossroads/network/WorkOrderListPayload.java"));
        check(entry.contains("String nameJson"), "row name travels as a serialized component");
        check(entry.contains("boolean claimed"), "row carries the claimed flag");
        check(entry.contains("String stage"), "row carries the raw stage name");
        check(!entry.contains("String status"), "row no longer carries a rendered status");

        final String network = Files.readString(
                Paths.get("src/main/java/com/YLFXM/rp/crossroads/network/Network.java"));
        check(!network.contains("\"Unclaimed\""), "server does not pre-render work order status");
        check(!network.contains("\"Claimed\""), "server does not pre-render work order status");
        check(network.contains("Component.Serializer.toJson"), "work order name is serialized as a component");
        check(!network.contains("getDisplayName().getString()"), "work order name is not flattened on the server");

        final String window = Files.readString(CLIENT.resolve("WindowWorkOrderList.java"));
        check(window.contains("Component.Serializer.fromJson"), "work order name is parsed on the client");
        check(window.contains("rp_crossroads.wo.type."), "work order type is translated");
        check(window.contains("rp_crossroads.wo.stage."), "work order stage is translated");

        for (final String type : List.of("build", "upgrade", "repair", "remove", "unknown")) {
            check(zh.has("rp_crossroads.wo.type." + type), "wo type key in zh_cn: " + type);
            check(en.has("rp_crossroads.wo.type." + type), "wo type key in en_us: " + type);
        }
        for (final String stage : List.of("clear", "build_solid", "clear_water", "clear_non_solids",
                "decorate", "spawn", "remove", "remove_water", "weak_solid")) {
            check(zh.has("rp_crossroads.wo.stage." + stage), "wo stage key in zh_cn: " + stage);
            check(en.has("rp_crossroads.wo.stage." + stage), "wo stage key in en_us: " + stage);
        }

        // The build tool's impact list carried a full English sentence across the wire.
        final String joint = Files.readString(ROAD.resolve("RoadJointWork.java"));
        check(!joint.contains("Released structures may be cleared"),
                "build tool impact warning is a key, not a sentence");
        check(joint.contains("rp_crossroads.repair.released_warning"), "impact warning key is emitted");
        check(zh.has("rp_crossroads.repair.released_warning"), "impact warning key in zh_cn");
        check(en.has("rp_crossroads.repair.released_warning"), "impact warning key in en_us");

        // The creative tab still carried the pre-rename name.
        check("模拟殖民地：阡陌纵横".equals(zh.get("itemGroup.rp_crossroads").getAsString()),
                "creative tab uses the Crossroads name");
        check("MineColonies: Crossroads".equals(en.get("itemGroup.rp_crossroads").getAsString()),
                "creative tab uses the Crossroads name");
    }

    private static JsonObject json(final String name) throws Exception {
        return JsonParser.parseString(Files.readString(LANG.resolve(name))).getAsJsonObject();
    }

    private static List<String> difference(final Set<String> a, final Set<String> b) {
        final List<String> out = new ArrayList<>();
        for (final String key : a) if (!b.contains(key)) out.add("-" + key);
        for (final String key : b) if (!a.contains(key)) out.add("+" + key);
        return out;
    }
}
