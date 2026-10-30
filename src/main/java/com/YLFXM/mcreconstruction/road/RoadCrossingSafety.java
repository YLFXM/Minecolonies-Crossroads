/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import net.minecraft.server.level.ServerLevel;

/** Resource-derived reservations for a foreign road that must remain untouched. */
public final class RoadCrossingSafety {
    public static int lampHeight(ServerLevel level,RoadPlan road) {
        var lamp=RoadUnitLibrary.get("lamp",level.registryAccess());
        return lamp.entries().stream().filter(e -> !e.placeholder() && RoadBlueprintOps.isConcrete(e.state()))
                .mapToInt(e -> e.pos().getY()-lamp.surfaceY()).max().orElse(0);
    }
    public static int upperFloor(int neighborFloor,int neighborLampHeight) {return Math.max(neighborFloor+3,neighborFloor+neighborLampHeight+1);}
    public static int lowerFloor(int neighborFloor,int ownLampHeight) {return Math.min(neighborFloor-3,neighborFloor-2-ownLampHeight);}
    public static boolean permits(ServerLevel level,RoadPlan own,RoadPlan peer,int order) {
        var overlap=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(peer));if(overlap==null)return true;
        var a=RoadHeightProfile.of(own);var b=RoadHeightProfile.of(peer);
        int reservedLamp=lampHeight(level,order>0 ? peer:own);
        for(int x=overlap.minX();x<=overlap.maxX();x++)for(int z=overlap.minZ();z<=overlap.maxZ();z++) {
            int y=a.row(own.axis()==net.minecraft.core.Direction.Axis.X ? x:z).baseY();
            int other=b.row(peer.axis()==net.minecraft.core.Direction.Axis.X ? x:z).baseY();
            if(order>0 ? y<upperFloor(other,reservedLamp) : order>=0 || y>lowerFloor(other,reservedLamp))return false;
        }
        return true;
    }
    private RoadCrossingSafety() { }
}
