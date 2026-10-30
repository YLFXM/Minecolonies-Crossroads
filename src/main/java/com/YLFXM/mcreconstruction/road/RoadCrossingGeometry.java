/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import net.minecraft.core.Direction;

/** Exact XZ footprints, independent of reserved lamp heights and render AABBs. */
public final class RoadCrossingGeometry {
    public record Rect(int minX,int minZ,int maxX,int maxZ) {
        public Rect { if(minX>maxX || minZ>maxZ) throw new IllegalArgumentException("Empty footprint"); }
        public boolean contains(int x,int z) { return x>=minX && x<=maxX && z>=minZ && z<=maxZ; }
        public Rect intersect(Rect b) {
            int x=Math.max(minX,b.minX),z=Math.max(minZ,b.minZ),xx=Math.min(maxX,b.maxX),zz=Math.min(maxZ,b.maxZ);
            return x>xx || z>zz ? null : new Rect(x,z,xx,zz);
        }
        public int first(RoadPlan p) { return p.axis()==Direction.Axis.X ? minX : minZ; }
        public int last(RoadPlan p) { return p.axis()==Direction.Axis.X ? maxX : maxZ; }
    }
    public static Rect body(RoadPlan p) { return footprint(p,0,p.width()-1); }
    public static Rect walk(RoadPlan p) { return footprint(p,p.walkRange().from(),p.walkRange().to()); }
    private static Rect footprint(RoadPlan p,int first,int last) {
        int a=(p.axis()==Direction.Axis.X ? p.anchorPos().getZ() : p.anchorPos().getX())+p.widthSide()*first;
        int b=(p.axis()==Direction.Axis.X ? p.anchorPos().getZ() : p.anchorPos().getX())+p.widthSide()*last;
        return p.axis()==Direction.Axis.X ? new Rect(p.minAlong(),Math.min(a,b),p.maxAlong(),Math.max(a,b))
                : new Rect(Math.min(a,b),p.minAlong(),Math.max(a,b),p.maxAlong());
    }
    public static int platform(RoadPlan p,Rect intersection) {
        RoadHeightProfile profile=RoadHeightProfile.of(p); Integer height=null;
        for(int i=intersection.first(p);i<=intersection.last(p);i++) {
            var row=profile.row(i);
            if(row.type()!=CellType.FLAT) throw new IllegalArgumentException("The crossing on "+p.displayName()+" contains stairs; prepare a joint flat platform first.");
            if(height!=null && height!=row.baseY()) throw new IllegalArgumentException("A junction must be one level platform.");
            height=row.baseY();
        }
        if(height==null) throw new IllegalArgumentException("Empty junction"); return height;
    }
    public static String pair(UUID a,UUID b) { return a.compareTo(b)<0 ? a+"/"+b : b+"/"+a; }
    private RoadCrossingGeometry() { }
}
