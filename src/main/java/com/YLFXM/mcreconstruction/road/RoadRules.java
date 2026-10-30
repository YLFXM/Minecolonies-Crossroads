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

/** Pure road-state policy shared by the UI and authoritative server handlers. */
public final class RoadRules {
    public static boolean isPlanningCommand(RoadPlacementMode mode) {
        return mode==RoadPlacementMode.PLAN || mode==RoadPlacementMode.CONFIGURE;
    }
    public static boolean canEdit(final RoadPlan plan) {
        return plan != null && !plan.isRepairBusy() && !plan.hasInterruptedConstruction() && (plan.state() == RoadState.DRAFT
                || plan.state() == RoadState.PLANNED && (plan.appliedBounds()==null && plan.appliedGeometry()==null || plan.allowBlueprintEdit())
                || (plan.state() == RoadState.BUILT && plan.allowBlueprintEdit()));
    }

    public static boolean canCreate(final RoadPlan plan) {
        return plan != null && !plan.isRepairBusy() && plan.state() == RoadState.DRAFT;
    }
    public static boolean canChangePlatform(final RoadPlan own,final RoadPlan peer) {
        return own!=null && peer!=null && !own.id().equals(peer.id()) && canEdit(own) && canEdit(peer);
    }

    public static boolean canToggleEdit(final RoadPlan plan) {
        return plan != null && !plan.isRepairBusy() && !plan.hasInterruptedConstruction() && (plan.state() == RoadState.DRAFT
                || plan.state() == RoadState.PLANNED || plan.state() == RoadState.BUILT);
    }

    public static boolean canRepair(final RoadPlan plan) {
        return plan!=null && !plan.isRepairBusy() && (plan.state()==RoadState.PLANNED || plan.state()==RoadState.BUILT) && !plan.segments().isEmpty();
    }

    public static boolean canRemove(final RoadPlan plan) {
        return plan != null && plan.state()!=RoadState.REMOVING && plan.state()!=RoadState.REMOVED;
    }

    public static boolean canPublish(final RoadPlan plan) {
        return plan != null && plan.state() != RoadState.BUILT
                && plan.state() != RoadState.REMOVING && plan.state() != RoadState.REMOVED;
    }

    private RoadRules() { }
}
