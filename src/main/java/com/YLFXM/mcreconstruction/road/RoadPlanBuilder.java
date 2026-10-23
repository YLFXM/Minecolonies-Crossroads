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

import com.YLFXM.mcreconstruction.Config;
import com.YLFXM.mcreconstruction.MCReconstruction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds a straight, axis-aligned road plan from two endpoints. Intersections
 * are derived from the existing road index; no separate intersection unit is
 * created.
 */
public final class RoadPlanBuilder {
    public record Result(RoadPlan plan, RoadBounds bounds, List<RoadPlan> intersections) {
    }

    public static Result build(final ServerLevel level,
                               final BlockPos controllerPos,
                               final Direction controllerFacing,
                               final BlockPos a,
                               final BlockPos b,
                               final String pack,
                               final String variant,
                               final int width,
                               final int walkWidth,
                               final int widthSide,
                               final int lampSpacing) {
        return build(level, controllerPos, controllerPos, controllerFacing, a, b, pack, variant,
                width, walkWidth, widthSide, lampSpacing);
    }

    public static Result build(final ServerLevel level,
                               final BlockPos anchorPos,
                               final BlockPos controllerPos,
                               final Direction controllerFacing,
                               final BlockPos a,
                               final BlockPos b,
                               final String pack,
                               final String variant,
                               final int width,
                               final int walkWidth,
                               final int widthSide,
                               final int lampSpacing) {
        // Flat MVP: neither endpoint's Y is used. The surface height is decided
        // by the anchor (see below), so clicking A/B at different heights on
        // uneven ground still produces one level road.
        final boolean sameX = a.getX() == b.getX();
        final boolean sameZ = a.getZ() == b.getZ();
        if (sameX == sameZ) {
            throw new IllegalArgumentException("Endpoints must share exactly one coordinate (x or z).");
        }

        final Direction.Axis axis = sameX ? Direction.Axis.Z : Direction.Axis.X;
        final int minX = Math.min(a.getX(), b.getX());
        final int maxX = Math.max(a.getX(), b.getX());
        final int minZ = Math.min(a.getZ(), b.getZ());
        final int maxZ = Math.max(a.getZ(), b.getZ());
        final int length = (axis == Direction.Axis.X ? maxX - minX : maxZ - minZ) + 1;
        if (length > Math.max(1, Config.maxRoadLength())) {
            throw new IllegalArgumentException("Road length " + length + " exceeds max " + Config.maxRoadLength());
        }

        final int along = axis == Direction.Axis.X ? anchorPos.getX() : anchorPos.getZ();
        final int minAlong = axis == Direction.Axis.X ? minX : minZ;
        final int maxAlong = axis == Direction.Axis.X ? maxX : maxZ;
        if (along < minAlong || along > maxAlong) {
            throw new IllegalArgumentException("Road anchor is outside the endpoint interval.");
        }
        // Anchor must lie on the AB line, not merely inside the interval.
        if (axis == Direction.Axis.X ? anchorPos.getZ() != a.getZ() : anchorPos.getX() != a.getX()) {
            throw new IllegalArgumentException("Road anchor must lie on the AB line.");
        }

        // The road surface comes from the ANCHOR, not from the endpoints: the
        // anchor block sits on the ground next to the controller, so the
        // pavement goes one block below it. A/B only mark the two ends, which
        // keeps selection usable on uneven ground and leaves the vertical
        // layout to stairs later.
        final BlockPos start = new BlockPos(minX, anchorPos.getY() - 1, minZ);
        final RoadPlan plan = new RoadPlan(java.util.UUID.randomUUID(), level.dimension(), anchorPos, controllerPos,
                controllerFacing, false, pack, variant, width,
                WalkRange.centered(width, walkWidth), widthSide, lampSpacing);
        plan.addSegment(new RoadSegment(start, length, axis, 0, SegmentKind.ROAD));
        // Which end the player clicked first is not recoverable from the
        // normalised segment start, but stairs are stored relative to travel, so
        // it has to be recorded.
        final int alongA = axis == Direction.Axis.X ? a.getX() : a.getZ();
        final int alongB = axis == Direction.Axis.X ? b.getX() : b.getZ();
        plan.setTravelDir(Integer.signum(alongB - alongA));
        // Kept on the plan so the ghost preview survives a restart and so the
        // server can tell when a player re-picked A/B on a planned road.
        plan.setEndpoints(a, b);
        plan.setState(RoadState.PLANNED);

        final RoadBounds bounds = plan.bounds(Config.defaultLampReserveHeight());
        final List<RoadPlan> intersections = new ArrayList<>();
        for (final RoadPlan other : RoadManager.get(level).all()) {
            if (other.id().equals(plan.id()) || other.dimension() != level.dimension()) {
                continue;
            }
            if (other.segments().isEmpty()) {
                continue;
            }
            final RoadBounds otherBounds = other.bounds(0);
            if (bounds.intersects(otherBounds)) {
                intersections.add(other);
            }
        }

        if (Config.debugLog()) {
            MCReconstruction.LOGGER.info("Road plan {}: length {}, bounds {}x{}x{}, intersections {}",
                    plan.id(), length, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(), intersections.size());
        }
        return new Result(plan, bounds, intersections);
    }

    private RoadPlanBuilder() {
    }
}
