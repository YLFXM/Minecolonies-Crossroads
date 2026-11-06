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

import java.util.Set;

/** Pure road-state policy shared by the UI and authoritative server handlers. */
public final class RoadRules {
    /** Individual busy facts an entry point may care about; callers pass the set they need. */
    public enum Busy { SESSION, INTERRUPTED, BUILDING, REMOVING }
    /** Proposal/row edits stop for an open session or a frozen interruption. */
    public static final Set<Busy> EDIT = Set.of(Busy.SESSION,Busy.INTERRUPTED);
    /** Anything that may rewrite a road or its neighbour while the world changes. */
    public static final Set<Busy> MUTATION = Set.of(Busy.SESSION,Busy.INTERRUPTED,Busy.BUILDING,Busy.REMOVING);
    /** Proposal regeneration must not race a live session or native placement. */
    public static final Set<Busy> PUBLISH = Set.of(Busy.SESSION,Busy.BUILDING,Busy.REMOVING);
    public static boolean busy(final RoadPlan plan,final Set<Busy> checks) {
        if(plan==null) return false;
        for(final Busy check:checks) {
            switch(check) {
                case SESSION -> {if(plan.isRepairBusy())return true;}
                case INTERRUPTED -> {if(plan.hasInterruptedConstruction())return true;}
                case BUILDING -> {if(plan.state()==RoadState.BUILDING)return true;}
                case REMOVING -> {if(plan.state()==RoadState.REMOVING)return true;}
            }
        }
        return false;
    }
    public static boolean canEdit(final RoadPlan plan) {
        return plan != null && !busy(plan,EDIT) && (plan.state() == RoadState.DRAFT
                || plan.state() == RoadState.PLANNED && (plan.appliedBounds()==null && plan.appliedGeometry()==null || plan.allowBlueprintEdit())
                || (plan.state() == RoadState.BUILT && plan.allowBlueprintEdit()));
    }

    public static boolean canCreate(final RoadPlan plan) {
        return plan != null && !busy(plan,Set.of(Busy.SESSION)) && plan.state() == RoadState.DRAFT;
    }
    public static boolean canChangePlatform(final RoadPlan own,final RoadPlan peer) {
        return own!=null && peer!=null && !own.id().equals(peer.id()) && canEdit(own) && canEdit(peer);
    }

    public static boolean canToggleEdit(final RoadPlan plan) {
        return plan != null && !busy(plan,EDIT) && (plan.state() == RoadState.DRAFT
                || plan.state() == RoadState.PLANNED || plan.state() == RoadState.BUILT);
    }

    public static boolean canRepair(final RoadPlan plan) {
        return plan!=null && !busy(plan,Set.of(Busy.SESSION))
                && (plan.state()==RoadState.PLANNED || plan.state()==RoadState.BUILT) && !plan.segments().isEmpty();
    }

    public static boolean canRemove(final RoadPlan plan) {
        return plan != null && plan.state()!=RoadState.REMOVING && plan.state()!=RoadState.REMOVED;
    }

    /**
     * Next relation selected by the panel button. Without authorization, same-level is not
     * reachable: strict avoidance only offers overpass/underpass. With authorization the
     * original 0→1→2→0 cycle is kept.
     */
    public static int nextRelationMode(final int current,final boolean authorized) {
        if(authorized) return (current+1)%3;
        return current==1 ? 2:1;
    }

    public static boolean canPublish(final RoadPlan plan) {
        return plan != null && plan.state() != RoadState.BUILT
                && plan.state() != RoadState.REMOVING && plan.state() != RoadState.REMOVED;
    }

    private RoadRules() { }
}
