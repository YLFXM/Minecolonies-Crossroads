/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.road;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Shared geometry validation and identity/diff transfer; performs no world writes. */
public final class RoadReplanning {
    /** A preview is the same owned draft, not a new anonymous road. */
    public static RoadPlan draftPreview(RoadPlan old,RoadPlan candidate,UUID actor,boolean mirror) {
        inherit(old,candidate,actor,true);candidate.setMirror(mirror);
        if(!old.segments().isEmpty() && old.axis()!=candidate.axis())candidate.clearRowOverrides();
        return candidate;
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
        plan.adoptCommittedRows(old.rowOverrides());
        plan.adoptPendingRows(old.pendingRows());
        if (!plan.segments().isEmpty()) plan.stageRemap(plan.minAlong(), plan.maxAlong());
    }

    /** Save referenceable candidate geometry; it remains DRAFT and never commits rows/world blocks. */
    public static boolean acceptDraftRows(final RoadPlan draft, final RoadPlan preview) {
        if (!RoadRules.canCreate(draft) || !RoadRules.canEdit(draft)) return false;
        preview.stageRemap(preview.minAlong(), preview.maxAlong());
        if (draft.pendingRows().equals(preview.pendingRows())
                && draft.planning().save().equals(preview.planning().save())
                && draft.segments().equals(preview.segments()) && draft.mirror()==preview.mirror()
                && java.util.Objects.equals(draft.endpointA(),preview.endpointA()) && java.util.Objects.equals(draft.endpointB(),preview.endpointB())
                && draft.lastDroppedRows() == preview.lastDroppedRows()) return false;
        draft.clearSegments();preview.segments().forEach(draft::addSegment);
        draft.setEndpoints(preview.endpointA(),preview.endpointB());draft.setTravelDir(preview.travelDir());draft.setMirror(preview.mirror());
        draft.adoptPendingRows(preview.pendingRows());
        draft.adoptPlanning(preview.planning());
        draft.setLastDroppedRows(preview.lastDroppedRows());
        return true;
    }

    private RoadReplanning() { }
}
