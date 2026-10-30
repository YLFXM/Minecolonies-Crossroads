/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.server.level.ServerLevel;

/**
 * The single maintenance point for row-level locks. Locks are derived from recorded relations only,
 * never from a live scan for overlapping roads, so planning never has to probe a third road.
 */
public final class RoadLockReconcile {
    public static void apply(ServerLevel level, RoadPlan road) {
        apply(road, id -> RoadManager.get(level).get(id));
    }

    public static void apply(RoadPlan road, java.util.function.Function<UUID, RoadPlan> peers) {
        Map<Integer, RoadPlanningState.Lock> next = new TreeMap<>();
        if (road.segments().isEmpty()) {
            road.planning().locks(next);
            return;
        }
        int anchor = RoadHeightProfile.anchorAlong(road);
        if (anchor >= road.minAlong() && anchor <= road.maxAlong()) {
            next.put(anchor, new RoadPlanningState.Lock(true, road.anchorPos().getY()));
        }
        for (var entry : road.planning().crossings().entrySet()) {
            var crossing = entry.getValue();
            if (crossing.layered()) continue;
            RoadPlan peer = peers.apply(entry.getKey());
            if (peer == null || peer.segments().isEmpty()) continue;
            var walk = RoadCrossingGeometry.walk(road).intersect(RoadCrossingGeometry.walk(peer));
            if (walk == null) continue;
            final int height;
            try {
                height = (crossing.platform() != null && peer.appliedBounds() == null
                        ? crossing.platform() : RoadCrossingGeometry.platform(peer, walk)) + 1;
            } catch (final IllegalArgumentException ex) {
                MCReconstruction.LOGGER.debug("Locked rows skipped on {}: {}", road.id(), ex.getMessage());
                continue;
            }
            for (int row = walk.first(road); row <= walk.last(road); row++) {
                if (row >= road.minAlong() && row <= road.maxAlong()) next.put(row, new RoadPlanningState.Lock(false, height));
            }
        }
        road.planning().locks(next);
    }

    /** Heals plans saved before locks existed: they still carry relation records, just no lock rows. */
    public static void ensure(ServerLevel level, RoadPlan road) {
        if (!road.segments().isEmpty() && !road.planning().crossings().isEmpty() && road.planning().locks().isEmpty()) {
            apply(level, road);
        }
    }

    /** Rows the planner may bend when working on [first,last]: bounded by the nearest locks on both sides. */
    public static int[] span(RoadPlan road, int first, int last) {
        int lo = road.minAlong(), hi = road.maxAlong();
        for (int row = first - 1; row >= road.minAlong(); row--) {
            if (road.planning().locked(row)) { lo = row; break; }
        }
        for (int row = last + 1; row <= road.maxAlong(); row++) {
            if (road.planning().locked(row)) { hi = row; break; }
        }
        return new int[]{lo, hi};
    }

    private RoadLockReconcile() { }
}
