/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.YLFXM.rp.crossroads.road;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Shared geometry validation, identity/diff transfer and the replanning handoff; performs no world block writes. */
public final class RoadReplanning {
    /** A preview is the same owned draft, not a new anonymous road. */
    public static RoadPlan draftPreview(RoadPlan old,RoadPlan candidate,UUID actor,boolean mirror) {
        inherit(old,candidate,actor,true);candidate.setMirror(mirror);
        clearRowsOnAxisChange(old,candidate);
        return candidate;
    }

    /**
     * Common tail of the two real re-planning entries (re-picked A/B and Create Blueprint):
     * keep the applied snapshot, stop the completion watch, then carry identity and row tables over.
     * Planning actions are deliberately not routed through here: they have no remap and no active build.
     */
    public static void handoff(final ServerLevel level,final RoadPlan old,final RoadPlan plan,
                               final UUID player,final boolean keepDraft) {
        RoadBlueprintStore.preserveBuilt(level,old);
        RoadBuildWatcher.stop(old);
        inherit(old,plan,player,keepDraft);
    }

    /** Sole axis-change row reset. Callers decide whether the extra draft-only gate applies. */
    public static boolean clearRowsOnAxisChange(final RoadPlan old,final RoadPlan plan) {
        if(old==null || old.segments().isEmpty() || old.axis()==plan.axis())return false;
        plan.clearRowOverrides();
        return true;
    }

    /** Sole wording for the axis-change notice. R8 will route it through the throttled notice outlet. */
    public static void noticeAxisChanged(final ServerPlayer player) {
        RoadNotices.chat(player,null,"axis_changed","rp_crossroads.replan.axis_changed");
    }
    public static BlockPos[] orient(final RoadPlan old, final BlockPos a, final BlockPos b) {
        if (old == null || RoadRules.canCreate(old)) return new BlockPos[]{a, b};
        final boolean x = old.axis() == Direction.Axis.X;
        final int anchor = x ? old.anchorPos().getX() : old.anchorPos().getZ();
        final int storedSide = Integer.signum(old.alongOfA() - anchor);
        final int sideA = Integer.signum((x ? a.getX() : a.getZ()) - anchor);
        final int sideB = Integer.signum((x ? b.getX() : b.getZ()) - anchor);
        return storedSide != 0 && sideA != storedSide && sideB == storedSide
                ? new BlockPos[]{b, a} : new BlockPos[]{a, b};
    }

    public static boolean pointOnLine(final RoadPlan plan, final BlockPos point) {
        return RoadRules.canCreate(plan) || (plan.axis() == Direction.Axis.X
                ? point.getZ() == plan.anchorPos().getZ() : point.getX() == plan.anchorPos().getX());
    }

    public static void validate(final RoadPlan old, final BlockPos a, final BlockPos b) {
        if (RoadRules.canCreate(old)) return;
        if (a == null || b == null) throw new IllegalArgumentException("Select two endpoints first.");
        final boolean alongX = old.axis() == Direction.Axis.X;
        final boolean sameZ = a.getZ() == b.getZ();
        final boolean sameX = a.getX() == b.getX();
        if (sameX == sameZ || alongX != sameZ) {
            throw new IllegalArgumentException("This road cannot change axis; keep the same x or z as before.");
        }
        if ((alongX ? a.getZ() : a.getX()) != (alongX ? old.anchorPos().getZ() : old.anchorPos().getX())) {
            throw new IllegalArgumentException("This road cannot move sideways; keep the original road line.");
        }
    }

    public static void inherit(final RoadPlan old, final RoadPlan plan, final UUID player,
                               final boolean keepDraft) {
        plan.setId(old.id());
        plan.setCreator(old.creator() != null ? old.creator() : player);
        plan.setDisplayName(old.displayName());
        plan.setRoadName(old.roadName());
        plan.setAllowBlueprintEdit(old.allowBlueprintEdit());
        plan.setAppliedBounds(old.appliedBounds());
        plan.setAppliedGeometry(old.appliedGeometry());
        plan.setInterruptedConstruction(old.interruptedConstruction());
        plan.adoptPlanning(old.planning());
        plan.setState(keepDraft && RoadRules.canCreate(old) ? RoadState.DRAFT : RoadState.PLANNED);
        plan.setWalkRange(old.walkRange());
        plan.adoptRows(carriedRows(old));
        if (!plan.segments().isEmpty()) plan.stageRemap(plan.minAlong(), plan.maxAlong());
    }

    /**
     * Row table a new proposal starts from when the range may have changed.
     *
     * <p>Carrying the previous proposal over wholesale loses applied state: shortening drops
     * rows from the proposal, so extending the road again rebuilt those rows from terrain
     * instead of from what the world already has. Inside the applied span the snapshot is the
     * only record of what was really built, so it is the base there and only the player's
     * manual edits are laid on top of it — dropping a row by shortening is not a player edit
     * and must not survive as one. Outside that span nothing is built yet, so the previous
     * proposal stays the source, exactly as before.</p>
     */
    static java.util.Map<Integer, RowState> carriedRows(final RoadPlan old) {
        final java.util.Map<Integer, RowState> out = new java.util.TreeMap<>();
        final RoadPlan applied = old.appliedGeometry() == null ? null : RoadPlan.load(old.appliedGeometry());
        final int lo = applied == null || applied.segments().isEmpty() ? 0 : applied.minAlong();
        final int hi = applied == null || applied.segments().isEmpty() ? -1 : applied.maxAlong();
        for (int row = lo; row <= hi; row++) {
            RowState state = applied.row(row);
            final RowState previous = old.rows().get(row);
            if (previous != null && previous.level() == RowState.RowLevel.MANUAL) {
                // The player's own edit is not part of what was built, so it has to be re-applied.
                state = new RowState(previous.type(), RowState.RowLevel.MANUAL, null, state.lamp(), state.base());
            }
            if (!state.isDefault()) out.put(row, state);
        }
        old.rows().forEach((row, state) -> {
            if (row >= lo && row <= hi) return;
            out.put(row, state);
        });
        return out;
    }

    /** Save referenceable candidate geometry; it remains DRAFT and never commits rows/world blocks. */
    public static boolean acceptDraftRows(final RoadPlan draft, final RoadPlan preview) {
        if (!RoadRules.canCreate(draft) || !RoadRules.canEdit(draft)) return false;
        preview.stageRemap(preview.minAlong(), preview.maxAlong());
        if (draft.pendingRows().equals(preview.pendingRows())
                && draft.planning().save().equals(preview.planning().save())
                && draft.segments().equals(preview.segments()) && draft.mirror()==preview.mirror()
                && java.util.Objects.equals(draft.endpointA(),preview.endpointA()) && java.util.Objects.equals(draft.endpointB(),preview.endpointB())
                && draft.lostRowCount() == preview.lostRowCount()) return false;
        draft.clearSegments();preview.segments().forEach(draft::addSegment);
        draft.setEndpoints(preview.endpointA(),preview.endpointB());draft.setTravelDir(preview.travelDir());draft.setMirror(preview.mirror());
        draft.adoptPendingRows(preview.pendingRows());
        // Intents the preview does not carry (dropped as out of range, or incompatible
        // after an axis reset) must not survive: the type goes back to flat, which also
        // removes the row from the derived diff.
        final java.util.Set<Integer> keep = new java.util.TreeSet<>(preview.pendingRows().keySet());
        for (final int row : new java.util.ArrayList<>(draft.pendingRows().keySet())) {
            if (!keep.contains(row)) draft.setRowType(row, CellType.FLAT);
        }
        draft.adoptPlanning(preview.planning());
        return true;
    }

    private RoadReplanning() { }
}
