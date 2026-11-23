package com.YLFXM.rp.crossroads.road;

import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

/**
 * Anchor ownership: which cell a plan's blueprint primary sits on.
 *
 * <p>Drives the decision seam behind {@link BlueprintAnchor} plus the pure
 * origin/lookup paths, so every branch of the lazy migration is covered without a
 * level: first bind, frozen plan, draft re-bind, refused rewrite, save round trip
 * and the frame move.</p>
 */
public final class AnchorOwnershipRegression {
    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static RoadPlan plan() {
        final RoadPlan plan = new RoadPlan(UUID.randomUUID(), Level.OVERWORLD,
                new BlockPos(10, 65, 20), new BlockPos(10, 65, 19),
                Direction.SOUTH, false, "minecolonies", "street", 4, WalkRange.of(1, 2), 1, 4);
        plan.addSegment(new RoadSegment(new BlockPos(5, 64, 20), 6, Direction.Axis.X, 0, SegmentKind.ROAD));
        return plan;
    }

    public static void main(final String[] args) {
        SharedConstants.tryDetectVersion();
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
        final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        final RoadPlan legacy = plan();
        final BlockPos anchor = legacy.anchorPos();
        final BlockPos reserved = RoadControllerMigration.candidates(legacy).get(0);

        // --- 1. No recorded anchor: the primary is still the marker block.
        check(BlueprintCells.blueprintAnchor(legacy).equals(anchor),
                "a plan without the field reads the primary from its anchor");
        final Blueprint image = RoadBlueprintGenerator.emptyBlueprint((short) 12, (short) 5, (short) 4, registries);
        image.setCachePrimaryOffset(new BlockPos(0, 1, 0));
        check(BlueprintCells.origin(legacy, image).equals(anchor.subtract(new BlockPos(0, 1, 0))),
                "legacy origin is anchor minus primary offset");

        // --- 2. Recorded anchor: same origin, primary on the recorded cell.
        final BlockPos min = new BlockPos(5, 63, 20);
        final RoadPlan bound = RoadPlan.load(legacy.save());
        bound.setBlueprintAnchor(reserved);
        image.setCachePrimaryOffset(reserved.subtract(min));
        check(BlueprintCells.origin(bound, image).equals(min),
                "a bound primary keeps the world origin on the image minimum");
        check(BlueprintCells.origin(bound, image).offset(image.getPrimaryBlockOffset()).equals(reserved),
                "origin plus primary offset lands on the recorded cell");
        check(!reserved.equals(anchor), "the reserved cell is a different cell from the marker anchor");

        // --- 3. Lazy migration: first bind moves the frame by the reserved-cell delta.
        final List<BlockPos> shifts = new ArrayList<>();
        final RoadPlan fresh = plan();
        final var first = BlueprintAnchor.decide(fresh, anchor, reserved, false,
                delta -> { shifts.add(delta); return true; });
        check(first.outcome() == BlueprintAnchor.Outcome.MIGRATED, "an unbound plan migrates on first use");
        check(first.anchor().equals(reserved), "migration lands on the reserved cell");
        check(shifts.size() == 1 && shifts.get(0).equals(reserved.subtract(anchor)),
                "migration rewrites the stored frames by reserved minus anchor");
        check(fresh.blueprintAnchor().equals(reserved), "the plan records the new primary");

        // A non-draft plan keeps what it recorded, even if the reserved cell moved on paper.
        final BlockPos moved = reserved.offset(0, 0, 1);
        final var kept = BlueprintAnchor.decide(fresh, anchor, moved, false, delta -> { shifts.add(delta); return true; });
        check(kept.outcome() == BlueprintAnchor.Outcome.KEPT && kept.anchor().equals(reserved),
                "a bound non-draft plan is frozen against later geometry noise");
        check(shifts.size() == 1, "a frozen plan rewrites nothing");

        // A draft still being shaped follows its own geometry (mirror flips the side).
        final var rebound = BlueprintAnchor.decide(fresh, anchor, moved, true,
                delta -> { shifts.add(delta); return true; });
        check(rebound.outcome() == BlueprintAnchor.Outcome.MIGRATED && rebound.anchor().equals(moved),
                "a draft re-binds to the cell its current geometry reserves");
        check(shifts.size() == 2 && shifts.get(1).equals(moved.subtract(reserved)),
                "draft re-bind shifts by new target minus previous target");

        // --- 4. A failed rewrite abandons the switch and leaves the plan alone.
        final RoadPlan stuck = plan();
        final var refused = BlueprintAnchor.decide(stuck, anchor, reserved, false, delta -> false);
        check(refused.outcome() == BlueprintAnchor.Outcome.REFUSED, "a refused rewrite reports the failure");
        check(refused.anchor().equals(anchor) && stuck.blueprintAnchor() == null,
                "a refused rewrite keeps the old frame and records nothing");
        final RoadPlan half = plan();
        half.setBlueprintAnchor(reserved);
        final var refusedAgain = BlueprintAnchor.decide(half, anchor, moved, true, delta -> false);
        check(refusedAgain.anchor().equals(reserved) && half.blueprintAnchor().equals(reserved),
                "a refused draft re-bind keeps the previously recorded cell");

        // --- 5. Reserve set: both anchors are never build targets.
        check(BlueprintCells.reservedCell(bound, anchor), "the marker anchor stays skipped");
        check(BlueprintCells.reservedCell(bound, bound.controllerPos()), "the controller stays skipped");
        check(BlueprintCells.reservedCell(bound, reserved), "the recorded primary is skipped");
        check(BlueprintCells.reservedCell(legacy, anchor), "an unbound plan still skips its anchor");
        check(!BlueprintCells.reservedCell(bound, new BlockPos(0, 0, 0)), "ordinary cells are not skipped");

        // --- Persistence: the field survives a save, and an old tag simply has none.
        final RoadPlan round = RoadPlan.load(bound.save());
        check(reserved.equals(round.blueprintAnchor()), "the recorded primary survives save/load");
        final var oldTag = legacy.save();
        oldTag.remove("blueprintAnchor");
        final RoadPlan oldLoad = RoadPlan.load(oldTag);
        check(oldLoad.blueprintAnchor() == null, "a save without the key reads back unbound");
        check(BlueprintCells.blueprintAnchor(oldLoad).equals(anchor), "an unbound save keeps the legacy primary");

        // Re-planning keeps identity, so it keeps the frame.
        final RoadPlan rePlanned = plan();
        RoadReplanning.inherit(bound, rePlanned, UUID.randomUUID(), false);
        check(reserved.equals(rePlanned.blueprintAnchor()), "a re-plan carries the recorded primary over");

        // --- Frame move: both anchors move, the snapshot moves with them, heights stay.
        final RoadPlan moving = plan();
        moving.setBlueprintAnchor(reserved);
        moving.setAppliedGeometry(moving.geometrySnapshot());
        final int targetRow = 7;
        final RoadHeightProfile profileBefore = RoadHeightProfile.of(moving);
        final int entryY = profileBefore.row(targetRow).entryY();
        final BlockPos anchorBefore = moving.anchorPos();
        final var delta = moving.reanchorTo(targetRow, entryY);
        check(delta != null, "a flat row can take the frame");
        check(moving.anchorPos().equals(anchorBefore.offset(delta)), "the geometry anchor moves by the delta");
        check(moving.blueprintAnchor().equals(reserved.offset(delta)), "the recorded primary moves with the frame");
        final RoadPlan applied = RoadPlan.load(moving.appliedGeometry());
        check(applied.anchorPos().equals(moving.anchorPos()) && applied.blueprintAnchor().equals(moving.blueprintAnchor()),
                "the applied snapshot carries both moved anchors");
        final BlockPos frameOffset = new BlockPos(2, 1, 3);
        check(moving.anchorPos().subtract(frameOffset.offset(delta)).equals(anchorBefore.subtract(frameOffset)),
                "the origin derived from the frame is unchanged by the move");
        final RoadHeightProfile profileAfter = RoadHeightProfile.of(moving);
        boolean heightsKept = true;
        for (int row = moving.minAlong(); row <= moving.maxAlong(); row++) {
            heightsKept &= profileAfter.row(row).entryY() == profileBefore.row(row).entryY()
                    && profileAfter.row(row).baseY() == profileBefore.row(row).baseY();
        }
        check(heightsKept, "row heights are unaffected by the blueprint anchor");

        System.out.println("PASS " + checks + " anchor ownership assertions");
    }

    private AnchorOwnershipRegression() {
    }
}
