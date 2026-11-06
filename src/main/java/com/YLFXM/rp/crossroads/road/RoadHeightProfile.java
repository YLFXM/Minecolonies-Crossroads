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

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/** Pure proposed geometry, integrated from the fixed flat anchor row in both directions. */
public final class RoadHeightProfile {
    public record Row(int along,CellType type,int entryY,int baseY) { }
    private final List<Row> rows;
    private final int minimum;
    private final int minBase;
    private final int maxBase;
    private RoadHeightProfile(List<Row> rows,int minimum,int minBase,int maxBase) {
        this.rows=List.copyOf(rows); this.minimum=minimum; this.minBase=minBase; this.maxBase=maxBase;
    }
    public static int anchorAlong(final RoadPlan plan) {
        return RoadAxisMath.alongOf(plan.axis(),plan.anchorPos());
    }
    public static RoadHeightProfile of(final RoadPlan plan) {
        final int min=plan.minAlong(),max=plan.maxAlong(),anchor=anchorAlong(plan);
        if(plan.segments().isEmpty()) return new RoadHeightProfile(List.of(),0,plan.anchorPos().getY()-1,plan.anchorPos().getY()-1);
        if(anchor<min || anchor>max) throw new IllegalArgumentException("Road anchor must be inside the endpoints.");
        if(plan.cellType(anchor)!=CellType.FLAT) throw new IllegalArgumentException("The anchor row must remain flat; edit that row to clear its old stair type.");
        final int direction=plan.travelDir();
        final int[] height=new int[Math.addExact(Math.subtractExact(max,min),1)];
        height[anchor-min]=plan.anchorPos().getY();
        for(int along=anchor;along+direction>=min && along+direction<=max;along+=direction)
            height[along+direction-min]=height[along-min]+plan.cellType(along).rise();
        for(int along=anchor-direction;along>=min && along<=max;along-=direction)
            height[along-min]=height[along+direction-min]-plan.cellType(along).rise();
        final List<Row> rows=new ArrayList<>();
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(int along=min;along<=max;along++) {
            final CellType type=plan.cellType(along);
            final int entry=height[along-min];
            final int base=type==CellType.FLAT ? entry-1 : entry+Math.min(0,type.rise());
            rows.add(new Row(along,type,entry,base)); low=Math.min(low,base); high=Math.max(high,base);
        }
        return new RoadHeightProfile(rows,min,low,high);
    }
    public List<Row> rows() { return rows; }
    public Row row(final int along) { return rows.get(along-minimum); }
    public int minBase() { return minBase; }
    public int maxBase() { return maxBase; }
    public static BlockPos position(final RoadPlan plan,final int along,final int lateral,final int y) {
        return plan.axis()==Direction.Axis.X
                ? new BlockPos(along,y,plan.anchorPos().getZ()+plan.widthSide()*lateral)
                : new BlockPos(plan.anchorPos().getX()+plan.widthSide()*lateral,y,along);
    }
    public static Direction uphill(final RoadPlan plan,final CellType type) {
        final Direction forward=plan.axis()==Direction.Axis.X
                ? (plan.travelDir()>0 ? Direction.EAST : Direction.WEST)
                : (plan.travelDir()>0 ? Direction.SOUTH : Direction.NORTH);
        return type==CellType.STAIR_DOWN ? forward.getOpposite() : forward;
    }
    public static String editSignature(final RoadPlan plan) {
        return plan.planning().revision()+":"+plan.travelDir()+":"+new java.util.TreeMap<>(plan.rowOverrides())+":"+new java.util.TreeMap<>(plan.pendingRows());
    }
}
