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

import net.minecraft.server.level.ServerLevel;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/** Resource-derived reservations for a foreign road that must remain untouched. */
public final class RoadCrossingSafety {
    public static int lampHeight(ServerLevel level,RoadPlan road) {
        var lamp=RoadUnitLibrary.get("lamp",level.registryAccess());
        return lamp.entries().stream().filter(e -> !e.placeholder() && RoadBlocks.isConcrete(e.state()))
                .mapToInt(e -> e.pos().getY()-lamp.surfaceY()).max().orElse(0);
    }
    public static int upperFloor(int neighborFloor,int neighborLampHeight) {return Math.max(neighborFloor+3,neighborFloor+neighborLampHeight+1);}
    public static int lowerFloor(int neighborFloor,int ownLampHeight) {return Math.min(neighborFloor-3,neighborFloor-2-ownLampHeight);}
    public static boolean permits(ServerLevel level,RoadPlan own,RoadPlan peer,int order) {
        var overlap=RoadCrossingGeometry.body(own).intersect(RoadCrossingGeometry.body(peer));if(overlap==null)return true;
        var a=RoadHeightProfile.of(own);var b=RoadHeightProfile.of(peer);
        int reservedLamp=lampHeight(level,order>0 ? peer:own);
        for(int x=overlap.minX();x<=overlap.maxX();x++)for(int z=overlap.minZ();z<=overlap.maxZ();z++) {
            int y=a.row(RoadAxisMath.along(own.axis(),x,z)).baseY();
            int other=b.row(RoadAxisMath.along(peer.axis(),x,z)).baseY();
            if(order>0 ? y<upperFloor(other,reservedLamp) : order>=0 || y>lowerFloor(other,reservedLamp))return false;
        }
        return true;
    }
    private RoadCrossingSafety() { }
}
