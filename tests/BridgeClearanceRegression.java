import com.YLFXM.rp.crossroads.road.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import java.nio.file.*;
import java.util.*;

/**
 * 0.3.6 收尾：上跨桥面必须有地基，净空 3 格，且幽灵只看形状。
 *
 * <p>Two things failed in play that these assertions pin down:</p>
 * <ol>
 *   <li>a bridge deck of gravel (main tiers 1-2) fell out of the crossing because the
 *       decorator cleared every cell under it — the deck needs the one cell it stands on,
 *       which is why the deck separation is clearance + deck + foundation;</li>
 *   <li>the ghost re-requested on the planning revision, so any bookkeeping bump made the
 *       client clear and rebuild it twice a second, which reads as "the ghost never
 *       updates". The signature is the visible shape only.</li>
 * </ol>
 */
public final class BridgeClearanceRegression {
    private static int checks;
    private static void check(final boolean ok, final String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    private static RoadPlan road(final int y) {
        final var p = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(0, y, 0), new BlockPos(0, y, -1),
                Direction.SOUTH, false, "minecolonies", "main", 5, WalkRange.of(1, 3), 1, 4);
        p.addSegment(new RoadSegment(new BlockPos(0, y - 1, -5), 21, Direction.Axis.Z, 0, SegmentKind.ROAD));
        return p;
    }

    /** A road on the other axis, crossing the {@link #road(int)} fixture at x 0..4 / z 0..4. */
    private static RoadPlan cross(final int y) {
        final var p = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(0, y, 0), new BlockPos(0, y, -1),
                Direction.SOUTH, false, "minecolonies", "main", 5, WalkRange.of(1, 3), 1, 4);
        p.addSegment(new RoadSegment(new BlockPos(-5, y - 1, 0), 21, Direction.Axis.X, 0, SegmentKind.ROAD));
        return p;
    }

    public static void main(final String[] args) throws Exception {
        check(RoadCrossings.LAYERED_SEPARATION == 5,
                "a layered crossing keeps five decks apart: three of room, one foundation, one deck");
        check(RoadCrossings.WALKING_CLEARANCE == 3, "three blocks of walking room");
        check(RoadCrossings.LAYERED_SEPARATION == RoadCrossings.WALKING_CLEARANCE + 2,
                "the separation is derived from the room, never a second magic number");

        check(RoadCrossings.hasWalkingClearance(65, 68), "exactly three blocks passes");
        check(!RoadCrossings.hasWalkingClearance(65, 67.999), "two blocks does not");

        // Flat base at 65, foreign deck at 64: over the crossing this road must reach 70
        // (64 + 3 room + 1 foundation + 1 deck) and, passing below, stop at 60.
        final int[] terrain = new int[21];
        Arrays.fill(terrain, 65);
        final CellType[] rows = new CellType[21];
        Arrays.fill(rows, CellType.FLAT);
        final var base = new RoadSlopeSolver.Profile(terrain, rows);
        final Map<Integer, Integer> pins = new HashMap<>(), lower = new HashMap<>(), upper = new HashMap<>();
        final Map<Integer, CellType> manual = new HashMap<>();
        RoadBridgeConstraints.add(base, 1, 10, 12, 64, 64, 1, null, pins, lower, upper, manual);
        check(lower.get(10) == 70 && lower.get(12) == 70, "overpass floor is the foreign deck plus five");
        final Map<Integer, Integer> pinsDown = new HashMap<>(), lowerDown = new HashMap<>(), upperDown = new HashMap<>();
        final Map<Integer, CellType> manualDown = new HashMap<>();
        RoadBridgeConstraints.add(base, 1, 10, 12, 64, 64, -1, null, pinsDown, lowerDown, upperDown, manualDown);
        check(upperDown.get(10) == 60 && upperDown.get(12) == 60, "underpass ceiling mirrors the overpass");
        // A stair row reaches one cell higher, so it needs one cell less on the way up and
        // sits one lower on the way down; both directions must agree with the same rule.
        final CellType[] stair = rows.clone();
        for (int i = 10; i <= 12; i++) stair[i] = CellType.STAIR_UP;
        final Map<Integer, Integer> pinsStair = new HashMap<>(), lowerStair = new HashMap<>(), upperStair = new HashMap<>();
        RoadBridgeConstraints.add(new RoadSlopeSolver.Profile(terrain, stair), 1, 10, 12, 64, 64, 1, null,
                pinsStair, lowerStair, upperStair, new HashMap<>());
        check(lowerStair.get(10) == 69, "a stair deck needs one cell less above the foreign deck");
        final Map<Integer, Integer> pinsStairDown = new HashMap<>(), lowerStairDown = new HashMap<>(), upperStairDown = new HashMap<>();
        RoadBridgeConstraints.add(new RoadSlopeSolver.Profile(terrain, stair), 1, 10, 12, 64, 64, -1, null,
                pinsStairDown, lowerStairDown, upperStairDown, new HashMap<>());
        check(upperStairDown.get(10) == 59, "and sits one cell lower below it");

        // The ghost signature is the shape. Bookkeeping bumps the planning revision on
        // purpose, and keying the preview on it made the client re-request forever.
        final RoadPlan plan = road(65);
        final String before = RoadHeightProfile.editSignature(plan);
        plan.planning().issue("a different message");
        plan.planning().planner(UUID.randomUUID());
        check(before.equals(RoadHeightProfile.editSignature(plan)),
                "a bookkeeping change does not move the ghost signature");
        plan.editRow(5, CellType.STAIR_UP);
        final String edited = RoadHeightProfile.editSignature(plan);
        check(!before.equals(edited), "a row edit does move it");
        final RoadPlan raised = road(67);
        check(!edited.equals(RoadHeightProfile.editSignature(raised)),
                "a different surface height moves it, even with identical row types");

        // The heal reads the real deck separation, so it can tell "already fine" from
        // "was built under the old three-block rule and can never be repaired again".
        check(RoadClearanceHeal.separation(road(65), cross(60)) == 5, "five decks apart reads as five");
        check(RoadClearanceHeal.separation(road(65), cross(62)) == 3, "a legacy three-block crossing reads as three");
        check(RoadClearanceHeal.separation(road(65), road(65)) == Integer.MAX_VALUE,
                "parallel roads are never a layered pair");

        final String planning = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadPlanningActions.java"));
        check(!planning.contains("restoreUnchangedManual"),
                "a confirmed override no longer restores the hand rows it just dropped");
        final String auto = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadAutoPlanning.java"));
        check(!auto.contains("p.pendingRows().forEach((row,type) -> p.editRow(row,type))"),
                "solving never re-marks the proposal's differences as hand edits");
        final String repair = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadRepair.java"));
        check(repair.contains("RoadClearanceHeal.heal(level,player,plan)"),
                "both repair entry points heal the clearance first");
        final String reasons = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadReasons.java"));
        check(reasons.contains("prefix(\"Layered crossings need\""),
                "the parameterised clearance reason still maps to its own message");
        final String merge = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadJointWork.java"));
        check(merge.contains("previous.state().isAir() && RoadBlocks.isSolidFill(next.state())"),
                "a deck foundation outranks the other road's air in the joint merge");
        check(merge.contains("previous.state()+"+"\" vs \"+next.state()"),
                "a real conflict names both states instead of only a position");
        final String structure = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadCrossings.java"));
        check(structure.contains("foundationCell(model,world)"),
                "the cell under a deck is structural, so a neighbour cannot clear it");

        final String aim = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/ClientRoadRenderer.java"));
        check(aim.contains("rp_crossroads.row.level."),
                "the crosshair hint tells the player whether the row is automatic, theirs, or fixed");
        final String aimState = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/client/RoadAim.java"));
        check(aimState.contains("level = bound.row(row).level()"),
                "the aim snapshot carries the row level it draws");

        final String network = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/network/Network.java"));
        check(!network.contains("oldPlanningRevision"),
                "a preview no longer syncs roads on the planning revision");
        check(network.contains("oldDisplay"), "it syncs only when the panel text it shows changed");
        final String height = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadHeightProfile.java"));
        check(!height.contains("plan.planning().revision()"), "the signature no longer carries the revision");
        final String crossings = Files.readString(Path.of(
                "src/main/java/com/YLFXM/rp/crossroads/road/RoadCrossings.java"));
        check(crossings.contains("boolean foundation=world.getY()==y-1"),
                "the cell the deck stands on is kept instead of cleared");
        check(crossings.contains("RoadBlocks.isSolidFill(info.getState())) {"),
                "foundation handling stays inside the fill-only branch");

        System.out.println("PASS " + checks + " bridge clearance/signature assertions");
    }

    private BridgeClearanceRegression() { }
}
