package com.YLFXM.rp.crossroads.road;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.UUID;

/**
 * Two-phase rebuild: what counts as a footprint change, and what survives a restart.
 *
 * <p>The world half (mask, demolition order, build order) needs a server; these
 * checks lock down the decisions that must not drift: the "demolish first"
 * boundary, and the shape payload riding on the demolition session.</p>
 */
public final class RebuildSessionRegression {
    private static int checks;

    private static void check(final boolean condition, final String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static RoadPlan plan(final int width, final WalkRange walk, final int widthSide, final boolean mirror) {
        final RoadPlan p = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD, new BlockPos(10, 65, 20),
                new BlockPos(10, 65, 19), Direction.SOUTH, mirror, "minecolonies", RoadUnitLibrary.STREETS,
                width, walk, widthSide, 4);
        p.addSegment(new RoadSegment(new BlockPos(5, 64, 20), 10, Direction.Axis.X, 0, SegmentKind.ROAD));
        p.setEndpoints(new BlockPos(5, 65, 20), new BlockPos(14, 65, 20));
        p.setState(RoadState.PLANNED);
        return p;
    }

    private static RoadRebuild.Shape shape(final int width, final WalkRange walk, final boolean mirror) {
        return new RoadRebuild.Shape("original", RoadUnitLibrary.STREETS, width, walk, 1, mirror);
    }

    public static void main(final String[] args) {
        final WalkRange streetWalk = WalkRange.of(1, 2);
        final WalkRange mainWalk = WalkRange.of(1, 3);

        // --- "demolish first" boundary -------------------------------------
        final RoadPlan sameWidth = plan(4, streetWalk, 1, false);
        check(!RoadRebuild.footprintDiffers(sameWidth, shape(4, streetWalk, false)),
                "same width, same walk band and same side is a plain repair");
        check(RoadRebuild.footprintDiffers(sameWidth, shape(5, streetWalk, false)),
                "a wider series has to be rebuilt");
        check(RoadRebuild.footprintDiffers(sameWidth, shape(4, mainWalk, false)),
                "a walk band the old road never owned has to be rebuilt");
        check(RoadRebuild.footprintDiffers(sameWidth, shape(4, streetWalk, true)),
                "a mirrored road has to be rebuilt");
        final RoadPlan mirrored = plan(4, streetWalk, -1, true);
        check(!RoadRebuild.footprintDiffers(mirrored, shape(4, streetWalk, true)),
                "a road that is already mirrored and stays mirrored is a plain repair");
        check(RoadRebuild.footprintDiffers(mirrored, shape(4, streetWalk, false)),
                "flipping back is a rebuild, not a repair");

        // A tier change is never a footprint change: it must stay inside the
        // ordinary repair path, which is what the junction cells are reconciled by.
        check(!RoadRebuild.footprintDiffers(sameWidth, new RoadRebuild.Shape(
                        "original", RoadUnitLibrary.STREETS, 4, streetWalk, 3, false)),
                "a tier change keeps the footprint");
        check(!RoadRebuild.footprintDiffers(sameWidth, new RoadRebuild.Shape(
                        "other-style", RoadUnitLibrary.STREETS, 4, streetWalk, 1, false)),
                "a same-width style change keeps the footprint");

        // --- shape payload on the demolition session ------------------------
        final RoadPlan target = plan(5, mainWalk, 1, false);
        target.setUnitLevel(2);
        target.adoptCommittedRows(java.util.Map.of(8, CellType.STAIR_UP));
        final CompoundTag payload = new CompoundTag();
        payload.putString("style", "original");
        payload.putString("series", RoadUnitLibrary.MAIN);
        payload.putInt("width", 5);
        payload.putInt("walkFrom", 1);
        payload.putInt("walkTo", 3);
        payload.putInt("level", 2);
        payload.putInt("originLevel", 1);
        payload.putBoolean("mirror", false);
        payload.put("target", target.save());
        payload.put("relations", new CompoundTag());

        final RoadRemovalSession session = new RoadRemovalSession(UUID.randomUUID(), false, 7);
        check(!session.rebuilds(), "a plain removal carries no rebuild payload");
        session.setRebuild(payload);
        check(session.rebuilds(), "a shape change marks the demolition as a rebuild");
        check(session.rebuild().getCompound("target").getInt("width") == 5,
                "the frozen target rides with the demolition");

        final RoadPlan live = plan(4, streetWalk, 1, false);
        live.setAppliedGeometry(live.geometrySnapshot());
        live.setRemovalSession(session);
        live.setState(RoadState.REMOVING);
        final RoadPlan reloaded = RoadPlan.load(live.save());
        check(reloaded.removalSession() != null && reloaded.removalSession().rebuilds(),
                "a restart mid-demolition still knows what to build afterwards");
        check(reloaded.removalSession().rebuild().getString("series").equals(RoadUnitLibrary.MAIN),
                "the series survives the round trip");
        check(RoadPlan.load(reloaded.removalSession().rebuild().getCompound("target")).unitLevel() == 2,
                "the planned tier survives the round trip");
        check(RoadPlan.load(reloaded.removalSession().rebuild().getCompound("target"))
                        .cellType(8) == CellType.STAIR_UP,
                "the planned rows survive the round trip");

        // The session is private server data: the client must never receive the
        // shape or the relations it is going to be built with.
        check(!live.saveForSync().contains("removalSession"),
                "the rebuild payload is not published in the road index");
        check(!live.saveForSync().toString().contains("originLevel"),
                "no rebuild field leaks through the sync snapshot");

        // A demolition in flight blocks every construction entry, rebuild or not.
        check(!RoadRules.canRepair(live) && !RoadRules.canCreate(live) && !RoadRules.canRemove(live),
                "a rebuild in flight cannot start a second one");

        System.out.println("RebuildSessionRegression: " + checks + " checks passed");
    }

    private RebuildSessionRegression() { }
}
