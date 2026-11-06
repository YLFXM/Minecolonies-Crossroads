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

import java.util.*;
import com.YLFXM.rp.crossroads.Crossroads;
import net.minecraft.server.level.ServerLevel;

/**
 * The single maintenance point for row-level locks. Locks are derived from recorded relations only,
 * never from a live scan for overlapping roads, so planning never has to probe a third road.
 */
public final class RoadLockReconcile {
    public static void apply(ServerLevel level, RoadPlan road) {
        apply(road,RoadJunctions.forRoad(level,road.id()),
                id -> { final RoadPlan live=RoadManager.get(level).get(id);
                    return live==null ? null : RoadCrossings.reference(level,live); },
                id -> { final RoadPlan live=RoadManager.get(level).get(id);
                    return live!=null && (live.appliedBounds()!=null || live.appliedGeometry()!=null); });
    }

    /**
     * Recomputes which rows are pinned and at what surface height.
     *
     * <p>The update is a difference: rows that stop being locked return to automatic,
     * and a manual row that becomes locked is upgraded rather than kept. Heights are
     * surface heights — the anchor row sits one block below the anchor block, and a
     * same-level junction row sits at the shared platform.</p>
     */
    public static void apply(RoadPlan road,List<RoadJunctions.Junction> junctions,
                             java.util.function.Function<UUID,RoadPlan> peers,
                             java.util.function.Function<UUID,Boolean> builtPeers) {
        Map<Integer, Integer> next = new TreeMap<>();
        if (!road.segments().isEmpty()) {
            int anchor = RoadHeightProfile.anchorAlong(road);
            if (anchor >= road.minAlong() && anchor <= road.maxAlong()) {
                next.put(anchor, road.anchorPos().getY() - 1);
            }
            for (var junction : junctions) {
                var crossing = RoadJunctions.ruleFor(junction, road.id());
                if (crossing.layered()) continue;
                UUID other = junction.a().equals(road.id()) ? junction.b() : junction.a();
                RoadPlan peer = peers.apply(other);
                if (peer == null || peer.segments().isEmpty()) continue;
                var walk = RoadCrossingGeometry.walk(road).intersect(RoadCrossingGeometry.walk(peer));
                if (walk == null) continue;
                final int surface;
                try {
                    surface = crossing.platform() != null && !builtPeers.apply(other)
                            ? crossing.platform() : RoadCrossingGeometry.platform(peer, walk);
                } catch (final IllegalArgumentException ex) {
                    Crossroads.LOGGER.debug("Locked rows skipped on {}: {}", road.id(), ex.getMessage());
                    continue;
                }
                for (int row = walk.first(road); row <= walk.last(road); row++) {
                    if (row >= road.minAlong() && row <= road.maxAlong()) next.put(row, surface);
                }
            }
        }
        for (int along : new ArrayList<>(road.lockedRows().keySet())) {
            if (!next.containsKey(along)) road.unlock(along);
        }
        next.forEach(road::lock);
    }

    /** Test-only view without a server level: every peer is treated as unbuilt. */
    public static void apply(RoadPlan road,List<RoadJunctions.Junction> junctions,
                             java.util.function.Function<UUID,RoadPlan> peers) {
        apply(road,junctions,peers,id -> false);
    }

    /** Heals plans saved before locks existed: they still carry relation records, just no locked rows. */
    public static void ensure(ServerLevel level, RoadPlan road) {
        if (!road.segments().isEmpty() && !RoadJunctions.forRoad(level,road.id()).isEmpty() && road.lockedRows().isEmpty()) {
            apply(level, road);
        }
    }

    /** Rows the planner may bend when working on [first,last]: bounded by the nearest locks on both sides. */
    public static int[] span(RoadPlan road, int first, int last) {
        int lo = road.minAlong(), hi = road.maxAlong();
        for (int row = first - 1; row >= road.minAlong(); row--) {
            if (road.row(row).locked()) { lo = row; break; }
        }
        for (int row = last + 1; row <= road.maxAlong(); row++) {
            if (road.row(row).locked()) { hi = row; break; }
        }
        return new int[]{lo, hi};
    }

    private RoadLockReconcile() { }
}
