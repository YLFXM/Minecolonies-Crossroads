/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.Map;

/** Bridge deltas are derived from the base curve, never from an already raised proposal. */
public final class RoadBridgeConstraints {
    public static void add(RoadSlopeSolver.Profile base,int anchor,int first,int last,int low,int high,int order,Integer selected,
            Map<Integer,Integer> pins,Map<Integer,Integer> lower,Map<Integer,Integer> upper,Map<Integer,CellType> manual) {
        int[] heights=base.heights();CellType[] rows=base.rows();int n=heights.length,shift=0;
        if(first<0 || last>=n || first>last || order==0)throw new IllegalArgumentException("Invalid bridge span.");
        if(selected!=null)shift=selected+(rows[first]==CellType.STAIR_UP ? 0 : 1)-heights[first];
        else for(int i=first;i<=last;i++) {
            if(order>0)shift=Math.max(shift,high+(rows[i]==CellType.STAIR_UP ? 3 : 4)-heights[i]);
            else shift=Math.min(shift,low-(rows[i]==CellType.STAIR_UP ? 3 : 2)-heights[i]);
        }
        for(int i=first;i<=last;i++) {
            if(order>0)lower.merge(i,high+(rows[i]==CellType.STAIR_UP ? 3 : 4),Math::max);
            else upper.merge(i,low-(rows[i]==CellType.STAIR_UP ? 3 : 2),Math::min);
            if(shift!=0 || selected!=null) {
                manual.putIfAbsent(i,rows[i]);put(pins,i,heights[i]+shift);
            }
        }
        // Return heights are preferences, never hard pins that can conflict with a nearby second crossing.
    }
    public static RoadSlopeSolver.Profile prefer(RoadSlopeSolver.Profile base,int anchor,int anchorHeight,Map<Integer,Integer> pins) {
        int[] natural=base.heights(),preferred=natural.clone();var fixed=new java.util.TreeMap<Integer,Integer>(pins);fixed.putIfAbsent(anchor,anchorHeight);
        var points=new java.util.ArrayList<>(fixed.entrySet());
        for(int k=1;k<points.size();k++) {
            var a=points.get(k-1);var b=points.get(k);int terrain=Integer.MAX_VALUE;
            for(int i=a.getKey();i<=b.getKey();i++)terrain=Math.min(terrain,natural[i]);
            int spare=b.getKey()-a.getKey()-Math.abs(a.getValue()-terrain)-Math.abs(b.getValue()-terrain)-1;
            if(spare<=3)for(int i=a.getKey()+1;i<b.getKey();i++)preferred[i]=(int)Math.round(a.getValue()+(double)(b.getValue()-a.getValue())*(i-a.getKey())/(b.getKey()-a.getKey()));
        }
        if(!points.isEmpty()) {
            var first=points.get(0);var last=points.get(points.size()-1);int end=natural.length-1;
            if(first.getKey()<anchor && first.getKey()-Math.abs(first.getValue()-natural[0])-1<=3)
                for(int i=0;i<first.getKey();i++)preferred[i]=first.getValue();
            if(last.getKey()>anchor && end-last.getKey()-Math.abs(last.getValue()-natural[end])-1<=3)
                for(int i=last.getKey()+1;i<=end;i++)preferred[i]=last.getValue();
        }
        return new RoadSlopeSolver.Profile(preferred,base.rows());
    }
    private static void put(Map<Integer,Integer> pins,int row,int height) {
        Integer old=pins.put(row,height);if(old!=null && old!=height)throw new IllegalArgumentException("Crossing height constraints conflict.");
    }
    private RoadBridgeConstraints() { }
}
