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

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/** Proof for repairing an existing crossing without acquiring a new neighbor modification right. */
final class RoadCrossingRestoration {
    static boolean geometry(ServerLevel level,RoadPlan proposed,RoadPlan peer) {
        var own=RoadBlueprintStore.appliedPlan(level,proposed);var other=RoadBlueprintStore.appliedPlan(level,peer);
        return own!=null && other!=null && geometry(level,proposed,own,peer,other);
    }
    static boolean geometry(ServerLevel level,RoadPlan proposed,RoadPlan own,RoadPlan peer,RoadPlan other) {
        return geometry(proposed,own,other,RoadCrossings.setting(level,proposed,peer),RoadCrossings.setting(level,own,other));
    }
    /** Level-free form: callers supply the proposed and the recorded relation. */
    static boolean geometry(RoadPlan proposed,RoadPlan own,RoadPlan other,RoadPlanningState.Crossing proposedRule,
                            RoadPlanningState.Crossing recordedRule) {
        var overlap=RoadCrossingGeometry.body(proposed).intersect(RoadCrossingGeometry.body(other));
        var previous=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(other));
        if(overlap==null || !overlap.equals(previous) || proposed.axis()!=own.axis() || proposed.widthSide()!=own.widthSide()
                || !proposed.walkRange().equals(own.walkRange()))return false;
        if(proposedRule.layered()!=recordedRule.layered() || proposedRule.order()!=recordedRule.order())return false;
        var target=RoadHeightProfile.of(proposed);var actual=RoadHeightProfile.of(own);
        for(int row=overlap.first(proposed);row<=overlap.last(proposed);row++)
            if(!target.row(row).equals(actual.row(row)))return false;
        return true;
    }
    static boolean permits(ServerLevel level,RoadPlan proposed,RoadPlan peer,Blueprint work) {
        return permits(level,proposed,peer,work,BlueprintCells.origin(proposed,work));
    }
    static boolean permits(ServerLevel level,RoadPlan proposed,RoadPlan peer,Blueprint work,BlockPos workOrigin) {
        var own=RoadBlueprintStore.appliedPlan(level,proposed);var other=RoadBlueprintStore.appliedPlan(level,peer);
        if(own==null || other==null || !geometry(level,proposed,own,peer,other))return false;
        return permits(proposed,own,other,RoadBlueprintStore.loadApplied(level,proposed),RoadBlueprintStore.loadApplied(level,peer),work,workOrigin);
    }
    static boolean permits(RoadPlan proposed,RoadPlan own,RoadPlan other,Blueprint ownImage,Blueprint peerImage,Blueprint work) {
        return permits(proposed,own,other,ownImage,peerImage,work,BlueprintCells.origin(proposed,work));
    }
    static boolean permits(RoadPlan proposed,RoadPlan own,RoadPlan other,Blueprint ownImage,Blueprint peerImage,Blueprint work,BlockPos origin) {
        if(ownImage==null || peerImage==null)return false;
        var profile=RoadHeightProfile.of(other);var body=RoadCrossingGeometry.body(other);
        for(var info:work.getBlockInfoAsList()) {
            var state=info.getState();if(RoadBlocks.isPlaceholder(state) && !RoadBlocks.isSolidFill(state))continue;
            var world=origin.offset(info.getPos());
            if(world.equals(other.controllerPos()) || world.equals(other.anchorPos()))return false;
            boolean reserved=false;
            var peerState=RoadCrossings.stateAt(peerImage,other,world);
            reserved|=RoadBlocks.isConcrete(peerState) || RoadBlocks.isSolidFill(peerState);
            if(body.contains(world.getX(),world.getZ())) {
                int floor=profile.row(RoadAxisMath.alongOf(other.axis(),world)).baseY();
                reserved|=world.getY()==floor-1 || RoadCrossingGeometry.walk(other).contains(world.getX(),world.getZ()) && world.getY()>=floor && world.getY()<=floor+2;
            }
            if(reserved && (!sameAt(work,ownImage,own,world,origin) || !sameAt(work,peerImage,other,world,origin)))return false;
        }
        return true;
    }
    private static boolean sameAt(Blueprint work,Blueprint other,RoadPlan otherModel,BlockPos world,BlockPos workOrigin) {
        var ap=world.subtract(workOrigin);var bp=world.subtract(BlueprintCells.origin(otherModel,other));
        if(!RoadCrossings.inside(work,ap) || !RoadCrossings.inside(other,bp) || !work.getBlockState(ap).equals(other.getBlockState(bp)))return false;
        var at=work.getTileEntities()[ap.getY()][ap.getZ()][ap.getX()];var bt=other.getTileEntities()[bp.getY()][bp.getZ()][bp.getX()];
        if(at!=null){at=at.copy();at.remove("x");at.remove("y");at.remove("z");}
        if(bt!=null){bt=bt.copy();bt.remove("x");bt.remove("y");bt.remove("z");}
        return Objects.equals(at,bt);
    }
    private RoadCrossingRestoration() { }
}
