/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Proof for repairing an existing crossing without acquiring a new neighbor modification right. */
final class RoadCrossingRestoration {
    static boolean geometry(ServerLevel level,RoadPlan proposed,RoadPlan peer) {
        var own=RoadBlueprintStore.appliedPlan(level,proposed);var other=RoadBlueprintStore.appliedPlan(level,peer);
        return own!=null && other!=null && geometry(proposed,own,peer,other);
    }
    static boolean geometry(RoadPlan proposed,RoadPlan own,RoadPlan peer,RoadPlan other) {
        var overlap=RoadCrossingGeometry.body(proposed).intersect(RoadCrossingGeometry.body(other));
        var previous=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(other));
        if(overlap==null || !overlap.equals(previous) || proposed.axis()!=own.axis() || proposed.widthSide()!=own.widthSide()
                || !proposed.walkRange().equals(own.walkRange()))return false;
        var a=RoadCrossings.setting(proposed,peer);var b=RoadCrossings.setting(own,other);
        if(a.layered()!=b.layered() || a.order()!=b.order())return false;
        var target=RoadHeightProfile.of(proposed);var actual=RoadHeightProfile.of(own);
        for(int row=overlap.first(proposed);row<=overlap.last(proposed);row++)
            if(!target.row(row).equals(actual.row(row)))return false;
        return true;
    }
    static boolean permits(ServerLevel level,RoadPlan proposed,RoadPlan peer,Blueprint work) {
        var own=RoadBlueprintStore.appliedPlan(level,proposed);var other=RoadBlueprintStore.appliedPlan(level,peer);
        if(own==null || other==null || !geometry(proposed,own,peer,other))return false;
        return permits(proposed,own,other,RoadBlueprintStore.loadApplied(level,proposed),RoadBlueprintStore.loadApplied(level,peer),work);
    }
    static boolean permits(RoadPlan proposed,RoadPlan own,RoadPlan other,Blueprint ownImage,Blueprint peerImage,Blueprint work) {
        if(ownImage==null || peerImage==null)return false;
        var origin=RoadBlueprintOps.origin(proposed,work);var profile=RoadHeightProfile.of(other);var body=RoadCrossingGeometry.body(other);
        for(var info:work.getBlockInfoAsList()) {
            var state=info.getState();if(RoadBlueprintOps.isPlaceholder(state) && !RoadBlueprintOps.isSolidFill(state))continue;
            var world=origin.offset(info.getPos());
            if(world.equals(other.controllerPos()) || world.equals(other.anchorPos()))return false;
            boolean reserved=false;
            var peerState=RoadCrossings.stateAt(peerImage,other,world);
            reserved|=RoadBlueprintOps.isConcrete(peerState) || RoadBlueprintOps.isSolidFill(peerState);
            if(body.contains(world.getX(),world.getZ())) {
                int floor=profile.row(other.axis()==net.minecraft.core.Direction.Axis.X ? world.getX():world.getZ()).baseY();
                reserved|=world.getY()==floor-1 || RoadCrossingGeometry.walk(other).contains(world.getX(),world.getZ()) && world.getY()>=floor && world.getY()<=floor+2;
            }
            if(reserved && (!sameAt(work,proposed,ownImage,own,world) || !sameAt(work,proposed,peerImage,other,world)))return false;
        }
        return true;
    }
    private static boolean sameAt(Blueprint a,RoadPlan am,Blueprint b,RoadPlan bm,BlockPos world) {
        var ap=world.subtract(RoadBlueprintOps.origin(am,a));var bp=world.subtract(RoadBlueprintOps.origin(bm,b));
        if(!RoadCrossings.inside(a,ap) || !RoadCrossings.inside(b,bp) || !a.getBlockState(ap).equals(b.getBlockState(bp)))return false;
        var at=a.getTileEntities()[ap.getY()][ap.getZ()][ap.getX()];var bt=b.getTileEntities()[bp.getY()][bp.getZ()][bp.getX()];
        if(at!=null){at=at.copy();at.remove("x");at.remove("y");at.remove("z");}
        if(bt!=null){bt=bt.copy();bt.remove("x");bt.remove("y");bt.remove("z");}
        return Objects.equals(at,bt);
    }
    private RoadCrossingRestoration() { }
}
