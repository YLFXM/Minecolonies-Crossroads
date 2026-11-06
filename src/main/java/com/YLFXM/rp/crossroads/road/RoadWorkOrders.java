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

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import net.minecraft.server.level.ServerLevel;

public final class RoadWorkOrders {
    public static boolean matches(final RoadPlan plan, final IWorkOrder order) {
        if (order == null || order.getStructurePath() == null) return false;
        if (RoadNativeRemoval.matches(plan,order)) return true;
        final String path = order.getStructurePath().replace('\\', '/');
        if (path.startsWith("mcreconstruction:road/")) {
            final String key = path.substring("mcreconstruction:road/".length());
            return key.equals(plan.id().toString()) || key.equals(plan.displayName());
        }
        if (!com.YLFXM.rp.crossroads.CustomRoadPack.isOurs(order.getStructurePack())) return false;
        return path.equals(RoadTemporaryPack.pathFor(plan))
                || path.equals("generated/road_" + plan.id() + ".blueprint");
    }

    public static boolean sameDimension(final RoadPlan plan, final IColony colony) {
        return plan.dimension().equals(colony.getDimension());
    }

    public static RoadPlan find(final ServerLevel level, final IWorkOrder order) {
        for (final RoadPlan plan : RoadManager.get(level).all()) {
            if (matches(plan, order)) return plan;
        }
        return null;
    }

    private RoadWorkOrders() { }
}
