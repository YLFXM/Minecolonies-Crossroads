import com.YLFXM.rp.crossroads.road.*;
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.nio.file.*;
import java.util.*;

/**
 * 0.3.6 收尾：手工行不能否决玩家的决定，但也不能被悄悄丢掉。
 *
 * <p>Three behaviours are locked here, because all three failed in play:</p>
 * <ol>
 *   <li>the panel owns a "redraw from terrain" entry again (the merge dropped it);</li>
 *   <li>a hand row offered for override is <em>counted on both roads</em> — counting
 *       only the primary is why a blocked relation switch refused without ever
 *       offering the second press;</li>
 *   <li>the reach of a confirmed press is measured, so the message states how many
 *       rows it will drop and the ladder is span-first, whole-road-second.</li>
 * </ol>
 */
public final class ManualOverrideRegression {
    private static int checks;
    private static void check(final boolean ok, final String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    private static RoadPlan road(final Direction.Axis axis, final int x, final int z, final int side) {
        final var p = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(x, 65, z), new BlockPos(x, 65, z - 1),
                Direction.SOUTH, false, "minecolonies", "street", 4, WalkRange.of(1, 2), side, 4);
        p.addSegment(new RoadSegment(new BlockPos(x - 5, 64, z - 5), 11, axis, 0, SegmentKind.ROAD));
        return p;
    }

    public static void main(final String[] args) throws Exception {
        final var x = road(Direction.Axis.X, 0, 0, 1);
        final var z = road(Direction.Axis.Z, 0, 0, 1);
        final RoadGeometry.Rect overlap = RoadCrossingGeometry.walk(x).intersect(RoadCrossingGeometry.walk(z));
        check(overlap != null, "the fixture really crosses");

        x.editRow(1, CellType.FLAT);
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.NONE, null) == 0,
                "without a confirmation nothing counts as droppable");
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.SPAN, null) == 1,
                "a hand row at the junction is in reach of the span");
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.ALL, null) == 1,
                "the whole-road reach still covers that row");

        // A hand edit on the neighbouring road bends its approach just as hard, and the
        // offer used to miss it: the count only ever looked at this road.
        z.editRow(2, CellType.STAIR_UP);
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.SPAN, null) == 2,
                "the neighbour's hand row counts too");
        z.clearManual(2);
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.SPAN, null) == 1,
                "clearing it drops the count back");

        // Far from the junction the span still reaches it (no other lock bounds it), but a
        // row that is out of the road's own range never counts: there is nothing to build.
        x.editRow(4, CellType.STAIR_DOWN);
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.SPAN, null) == 2,
                "a hand row further along the road is inside the bend span");
        x.clearManual(4);
        x.editRow(40, CellType.STAIR_DOWN);
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.ALL, null) == 1,
                "a row outside the geometry cannot be dropped or counted");
        x.clearManual(40);

        // The junction promotes the rows it covers to locked platforms, and a locked row is
        // no longer a hand row. This is the trap that made the count read zero in play.
        x.lock(1, 65);
        check(x.manualRows().isEmpty(), "a locked platform stops being a hand row");
        check(RoadPlanningActions.manualRowsInReach(x, z, RoadPlanningActions.Override.SPAN, null) == 0,
                "which is exactly why counting hand rows alone can read zero");

        final String xml = Files.readString(Path.of("src/main/resources/assets/rp_crossroads/gui/windowroadcontroller.xml"));
        check(xml.contains("id=\"redraw\""), "the controller panel has the redraw entry");
        check(xml.contains("id=\"redraw\"") && xml.contains("$(rp_crossroads.gui.reset_manual)"),
                "the redraw entry reuses the existing label key");
        check(xml.contains("size=\"248 169\""), "the panel grew one row for it");
        check(xml.indexOf("id=\"redraw\"") < xml.indexOf("id=\"issue\""),
                "the redraw entry sits above the issue line");
        final String payload = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/network/RoadControllerActionPayload.java"));
        check(payload.contains("REDRAW"), "the action exists on the wire");
        final String network = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/network/Network.java"));
        check(network.contains("case REDRAW ->"), "the server dispatches it");
        final String actions = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadPlanningActions.java"));
        check(actions.contains("public static void redraw(ServerPlayer player,RoadPlan plan)"),
                "redraw is its own entry, not a client-supplied action number");
        check(actions.contains("plan.mirror(),1,new UUID(0,0),0)"),
                "redraw reuses planning action 1 (clear rows, resample terrain)");
        check(actions.contains("if(!plan.hasEndpoints())"), "a road without endpoints is refused with a reason");
        check(actions.contains("manual_cleared_failed"),
                "a blocked change says so when clearing hand rows did not help");
        check(actions.contains("Override.ALL") && actions.contains("Override.SPAN"),
                "the ladder tries the span before the whole road");
        final String zh = Files.readString(Path.of("src/main/resources/assets/rp_crossroads/lang/zh_cn.json"));
        check(zh.contains("\"rp_crossroads.gui.reset_manual\": \"按地形重绘\""),
                "the panel button reads 按地形重绘");
        final String crossings = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/WindowRoadCrossings.java"));
        check(crossings.contains("private boolean ready()") && crossings.contains("rp_crossroads.select.endpoints_first"),
                "a dropped junction packet now reports why instead of doing nothing");

        System.out.println("PASS " + checks + " manual override assertions");
    }

    private ManualOverrideRegression() { }
}
