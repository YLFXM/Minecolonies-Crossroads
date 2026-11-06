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

import java.util.*;

/** Integer road profiles. Indices advance A-to-B; heights are row entry heights. */
public final class RoadSlopeSolver {
    public record Profile(int[] heights, CellType[] rows) {
        public Profile { heights=heights.clone(); rows=rows.clone(); }
        @Override public int[] heights() { return heights.clone(); }
        @Override public CellType[] rows() { return rows.clone(); }
    }
    private static final long INF=Long.MAX_VALUE/8;
    public static Profile solve(int[] terrain,int anchor,int anchorHeight,Map<Integer,Integer> pins,
                                Set<Integer> flat,Map<Integer,CellType> manual,int min,int max) {
        return constrained(terrain,anchor,anchorHeight,pins,flat,manual,Map.of(),Map.of(),min,max);
    }
    public static Profile constrained(int[] terrain,int anchor,int anchorHeight,Map<Integer,Integer> pins,
            Set<Integer> flat,Map<Integer,CellType> manual,Map<Integer,Integer> lower,Map<Integer,Integer> upper,int min,int max) {
        int n=terrain.length,span=max-min+1,states=span*3;
        if(n<1 || n>2048 || span<1 || span>512 || (long)n*states*3>12_000_000)
            throw new IllegalArgumentException("Road planning exceeds the computation budget.");
        if(anchor<0 || anchor>=n || anchorHeight<min || anchorHeight>max)
            throw new IllegalArgumentException("Anchor is outside the permitted profile.");
        Map<Integer,Integer> fixed=new HashMap<>(pins);
        Integer prior=fixed.put(anchor,anchorHeight);
        if(prior!=null && prior!=anchorHeight) throw new IllegalArgumentException("A junction conflicts with the fixed anchor height.");
        Set<Integer> level=new HashSet<>(flat); level.add(anchor);
        for(var e:fixed.entrySet()) if(e.getKey()<0 || e.getKey()>=n || e.getValue()<min || e.getValue()>max)
            throw new IllegalArgumentException("A required platform is outside the road or world height.");
        for(var e:manual.entrySet()) if(level.contains(e.getKey()) && e.getValue()!=CellType.FLAT)
            throw new IllegalArgumentException("A manually locked stair conflicts with a flat platform.");
        long[] cost=new long[states]; Arrays.fill(cost,INF);
        for(int h=min;h<=max;h++) cost[(h-min)*3+1]=0;
        int[][] parents=new int[n+1][states]; for(int[] a:parents) Arrays.fill(a,-1);
        for(int i=0;i<n;i++) {
            long[] next=new long[states]; Arrays.fill(next,INF);
            for(int state=0;state<states;state++) {
                if(cost[state]==INF) continue;
                int h=state/3+min,previous=state%3-1;
                if(fixed.containsKey(i) && fixed.get(i)!=h) continue;
                if(h<lower.getOrDefault(i,min) || h>upper.getOrDefault(i,max)) continue;
                for(int d=-1;d<=1;d++) {
                    if(level.contains(i) && d!=0 || manual.containsKey(i) && manual.get(i).rise()!=d) continue;
                    int out=h+d; if(out<min || out>max) continue;
                    int target=(out-min)*3+d+1;
                    long score=cost[state]+Math.abs((long)h-terrain[i])*8+(d==0 ? 0 : 2)
                            +(previous==d ? 0 : 1)+(previous*d<0 ? 5 : 0);
                    if(score<next[target]) { next[target]=score; parents[i+1][target]=state; }
                }
            }
            cost=next;
        }
        int best=-1; for(int i=0;i<states;i++) if(cost[i]<INF && (best<0 || cost[i]<cost[best])) best=i;
        if(best<0) throw new IllegalArgumentException("No profile can connect the fixed heights with the available rows and manual edits.");
        int[] heights=new int[n]; CellType[] rows=new CellType[n];
        for(int i=n-1;i>=0;i--) {
            int d=best%3-1,from=parents[i+1][best];
            if(from<0) throw new IllegalStateException("Incomplete road profile trace");
            heights[i]=from/3+min; rows[i]=type(d); best=from;
        }
        return new Profile(heights,rows);
    }
    /** Expand the correction window around junctions; keep the terrain draft elsewhere. */
    public static Profile balance(Profile initial,int anchor,int anchorHeight,Map<Integer,Integer> pins,
                                  Set<Integer> flat,Map<Integer,CellType> manual,int min,int max) {
        return balance(initial,anchor,anchorHeight,pins,flat,manual,Map.of(),Map.of(),min,max);
    }
    public static Profile balance(Profile initial,int anchor,int anchorHeight,Map<Integer,Integer> pins,
            Set<Integer> flat,Map<Integer,CellType> manual,Map<Integer,Integer> lower,Map<Integer,Integer> upper,int min,int max) {
        if(pins.isEmpty() && flat.isEmpty() && lower.isEmpty() && upper.isEmpty()) return initial;
        Set<Integer> focus=new HashSet<>(pins.keySet());focus.addAll(lower.keySet());focus.addAll(upper.keySet());
        int n=initial.rows.length,window=0;
        while(true) {
            Map<Integer,CellType> locked=new HashMap<>(manual);
            for(int i=0;i<n;i++) {
                boolean near=false;
                for(int p:focus) if(Math.abs(i-p)<=window) { near=true; break; }
                if(!near && !flat.contains(i)) locked.putIfAbsent(i,initial.rows[i]);
            }
            try { return constrained(initial.heights,anchor,anchorHeight,pins,flat,locked,lower,upper,min,max); }
            catch(IllegalArgumentException ex) {
                if(window>=n || ex.getMessage().contains("budget")) throw ex;
                window=window==0 ? 1 : Math.min(n,window*2);
            }
        }
    }
    public static CellType type(int rise) { return rise>0 ? CellType.STAIR_UP : rise<0 ? CellType.STAIR_DOWN : CellType.FLAT; }
    private RoadSlopeSolver() { }
}
