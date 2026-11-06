import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * R8: both language files must carry exactly the same key set, and the outlet keys must exist.
 *
 * <p>R8-fix1 added two guards: every {@code rp_crossroads.*} literal used by the source must
 * exist (otherwise the player sees the raw key), and the notices that used to describe one fact
 * twice must not come back.</p>
 */
public final class RoadNoticesRegression {
    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(final String[] args) throws Exception {
        final Set<String> en = keys(Paths.get("src/main/resources/assets/rp_crossroads/lang/en_us.json"));
        final Set<String> zh = keys(Paths.get("src/main/resources/assets/rp_crossroads/lang/zh_cn.json"));
        check(!en.isEmpty(), "english language file has keys");
        check(!zh.isEmpty(), "chinese language file has keys");
        check(en.equals(zh), "en_us and zh_cn carry exactly the same key set");

        for (final String key : new String[]{
                "rp_crossroads.road.locked",
                "rp_crossroads.road.session_open",
                "rp_crossroads.row.pinned",
                "rp_crossroads.select.endpoints_first",
                "rp_crossroads.wand.not_bound_controller",
                "rp_crossroads.planning.manual_conflict",
                "rp_crossroads.consent.invalid",
                "rp_crossroads.consent.reason_consumed",
                "rp_crossroads.generation_failed",
                "rp_crossroads.cell.flat",
                "rp_crossroads.cell.stair_up",
                "rp_crossroads.cell.stair_down",
                "rp_crossroads.plan.no_build_tool",
                "rp_crossroads.bind.wand",
                "rp_crossroads.edit.enabled",
                "rp_crossroads.repair.interrupted",
                "rp_crossroads.repair.crossing_interrupted",
                "rp_crossroads.remove.cancelled",
                "rp_crossroads.remove.no_builder_wild",
                "rp_crossroads.bind.anchor_placed",
                "rp_crossroads.bind.townhall_gone"}) {
            check(en.contains(key), "outlet key present: " + key);
        }

        // R8-fix1: each of these described the same fact as a surviving key, so it must stay gone.
        for (final String key : new String[]{
                "rp_crossroads.anchor_flat",
                "rp_crossroads.select.locked",
                "rp_crossroads.wand.built_locked",
                "rp_crossroads.wand.endpoints_first",
                "rp_crossroads.wand.not_bound",
                "rp_crossroads.plan.none_bound",
                "rp_crossroads.plan.none_here",
                "rp_crossroads.plan.generate_failed",
                "rp_crossroads.planning.height_manual_conflict",
                "rp_crossroads.consent.invalid_reason",
                "rp_crossroads.consent.refused",
                "rp_crossroads.construction.native_required",
                "rp_crossroads.remove.close_session",
                "rp_crossroads.repair.session_exists",
                "rp_crossroads.repair.refused",
                "rp_crossroads.bind.wand_already"}) {
            check(!en.contains(key), "duplicate notice removed: " + key);
        }

        // A key that only exists in one file would silently render as a raw key in the other language.
        final Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);
        final Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);
        check(onlyEn.isEmpty(), "no english-only keys: " + onlyEn);
        check(onlyZh.isEmpty(), "no chinese-only keys: " + onlyZh);

        // Every literal key the source passes to the outlet must exist, or the player sees the raw
        // key. Keys assembled by concatenation end with '.' or '_' and are checked by their parts.
        final List<String> missing = new ArrayList<>();
        final Pattern literal = Pattern.compile("\"(rp_crossroads\\.[A-Za-z0-9_.]+)\"");
        int scanned = 0;
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    final String key = matcher.group(1);
                    if (key.endsWith(".") || key.endsWith("_")) continue;
                    scanned++;
                    if (!en.contains(key)) missing.add(key + " @ " + file);
                }
            }
        }
        check(scanned > 100, "source key scan covered the notice surface: " + scanned);
        check(missing.isEmpty(), "every source key exists in lang: " + missing);

        // R8-fix3: an exception message is English and belongs in the log. Every player surface
        // (notice, action bar, panel status, crossing issue) must go through RoadNotices.reason,
        // which maps it to a lang key -- "道路蓝图无法生成：No bundled road unit matches ..." was
        // the failure that proved the old shape wrong.
        final List<String> leaks = new ArrayList<>();
        final String[] surfaces = {"RoadNotices.", "displayClientMessage(", "Component.translatable(",
                "Component.literal(", "planning().issue(", "crossingIssue(", "issues.add("};
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (final String line : Files.readAllLines(file)) {
                    if (!line.contains("getMessage()")) continue;
                    boolean surface = false;
                    for (final String marker : surfaces) if (line.contains(marker)) surface = true;
                    if (surface) leaks.add(file.getFileName() + ": " + line.trim());
                }
            }
        }
        check(leaks.isEmpty(), "no exception text reaches a player surface: " + leaks);

        // A removal rebuild is one batch notice, not one chat line per road. The old keys took
        // a road name as their argument; the new ones take a count.
        check(!en.contains("rp_crossroads.crossing.regenerated")
                && !en.contains("rp_crossroads.crossing.regeneration_failed"),
                "per-road rebuild notices are gone");
        check(en.contains("rp_crossroads.crossing.rebuilt_count")
                && en.contains("rp_crossroads.crossing.rebuilt_failed_count"),
                "batch rebuild notices are present");
        final String updates = Files.readString(Paths.get(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadCrossingUpdates.java"));
        check(updates.contains("RoadJunctions.forRoad(level,old.id())")
                && updates.contains("overlaps(body,road)")
                && updates.contains("batch.rebuilt++"),
                "removal rebuild is narrowed to adjacent roads and counted");

        // Every reason the table maps to must exist as a lang key, so a stale table entry cannot
        // silently show a raw key. The literal scan above already covers these strings.
        final java.lang.reflect.Method reasonKey = Class
                .forName("com.YLFXM.rp.crossroads.road.RoadNotices")
                .getDeclaredMethod("reasonKey", Throwable.class);
        reasonKey.setAccessible(true);
        check("rp_crossroads.reason.no_unit".equals(reasonKey.invoke(null,
                        new IllegalArgumentException("No bundled road unit matches this plan's style, variant and width."))),
                "a mapped failure renders its reason key");
        check("rp_crossroads.reason.internal".equals(reasonKey.invoke(null,
                        new IllegalArgumentException("something nobody mapped"))),
                "an unmapped failure degrades to the internal-error key");
        check("rp_crossroads.reason.junction_height_mismatch".equals(reasonKey.invoke(null,
                        new IllegalArgumentException("Junction height must match 3 exactly; rebalance the approach."))),
                "a reason that appends a value matches by prefix");

        // The outlet must never throw on a malformed argument: an enum used to build a
        // message that died inside the packet path and disconnected the player.
        final java.lang.reflect.Method text = Class
                .forName("com.YLFXM.rp.crossroads.road.RoadNotices")
                .getDeclaredMethod("text", String.class, Object[].class);
        text.setAccessible(true);
        final Object degraded = text.invoke(null, "rp_crossroads.row.edited",
                new Object[]{Integer.valueOf(1), FakeCell.FLAT});
        check("rp_crossroads.row.edited".equals(((net.minecraft.network.chat.Component) degraded).getString()),
                "a rejected notice argument degrades to the raw key instead of throwing");

        System.out.println("PASS " + checks + " notice assertions");
    }

    /** Stand-in for a value that the text component cannot carry. */
    private enum FakeCell { FLAT }

    private static Set<String> keys(final Path file) throws Exception {
        final String text = Files.readString(file);
        final Set<String> out = new TreeSet<>();
        final Matcher m = Pattern.compile("\"(rp_crossroads\\.[^\"]+)\"\\s*:").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private RoadNoticesRegression() { }
}
