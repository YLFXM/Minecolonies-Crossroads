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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;

/** Samples once per initial geometry, preserving explicit manual FLAT as well as slopes. */
public final class RoadAutoPlanning {
    public static String geometryKey(RoadPlan p) {
        return p.axis()+":"+p.minAlong()+":"+p.maxAlong()+":"+p.anchorPos()+":"+p.widthSide()+":"+p.travelDir();
    }
    public static int index(RoadPlan p,int along) { return p.travelDir()>0 ? along-p.minAlong() : p.maxAlong()-along; }
    public static int along(RoadPlan p,int index) { return p.travelDir()>0 ? p.minAlong()+index : p.maxAlong()-index; }
    public static String relationshipKey(ServerLevel level,RoadPlan p) {
        var key=new net.minecraft.nbt.CompoundTag();
        for(var other:RoadManager.get(level).all()) {
            if(other.id().equals(p.id()) || other.segments().isEmpty())continue;
            var reference=RoadCrossings.reference(level,other);
            if(RoadCrossingGeometry.body(p).intersect(RoadCrossingGeometry.body(reference))==null)continue;
            var item=RoadCrossingConsent.intent(level,reference);item.put("pair",RoadCrossingConsent.pairIntent(level,p,other));key.put(other.id().toString(),item);
        }
        return RoadCrossingConsent.digest(key);
    }
    public static void initialize(ServerLevel level,RoadPlan p,boolean force) {
        initialize(level,p,force,false);
    }
    public static void initialize(ServerLevel level,RoadPlan p,boolean force,boolean resample) {
        String key=geometryKey(p);
        String relations=relationshipKey(level,p);
        if(!force && !resample && key.equals(p.planning().sampleKey()) && relations.equals(p.planning().relationshipKey())) return;
        int n=p.length(); if(n<1 || n>2048) throw new IllegalArgumentException("Unsupported automatic road length.");
        int[] terrain;
        if(!resample && key.equals(p.planning().sampleKey()) && p.planning().terrain().length==n) terrain=p.planning().terrain();
        else {
            terrain=new int[n];
            var ownImage=RoadBlueprintStore.loadApplied(level,p);
            RoadPlan ownModel=p.appliedGeometry()==null ? p : RoadPlan.load(p.appliedGeometry());
            var ownProfile=RoadHeightProfile.of(ownModel);
            List<RoadPlan> actualRoads=new ArrayList<>();
            Map<UUID,Boolean> terrainAccess=new HashMap<>();
            for(var road:RoadManager.get(level).all()) {
                if(road.id().equals(p.id()) || road.segments().isEmpty())continue;
                if(RoadCrossingGeometry.body(p).intersect(RoadCrossingGeometry.body(road))==null && road.appliedBounds()==null)continue;
                // A road under construction contributes no applied content: its proposal is not
                // a terrain fact yet, so planning samples the world exactly as for an unbuilt road.
                var actual=RoadBlueprintStore.referenceIsApplied(level,road) ? RoadBlueprintStore.referencePlan(level,road):null;
                if(actual!=null){actualRoads.add(actual);terrainAccess.put(road.id(),RoadCrossingConsent.canPlan(level,p,road));}
            }
            for(int i=0;i<n;i++) {
                List<Integer> samples=new ArrayList<>();
                for(int lateral=p.walkRange().from();lateral<=p.walkRange().to();lateral++) {
                    BlockPos column=RoadHeightProfile.position(p,along(p,i),lateral,p.anchorPos().getY());
                    if(!level.hasChunkAt(column)) throw new IllegalArgumentException("Load the entire road before automatic planning.");
                    int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ())-1;
                    boolean roadSurface=false;
                    for(var actual:actualRoads) {
                        if(!RoadCrossingGeometry.body(actual).contains(column.getX(),column.getZ()))continue;
                        int floor=RoadHeightProfile.of(actual).row(RoadAxisMath.alongOf(actual.axis(),column)).baseY();
                        var live=RoadManager.get(level).get(actual.id());var rule=RoadCrossings.setting(level,p,live==null ? actual : live);
                        if(terrainAccess.getOrDefault(actual.id(),false) && (!rule.layered() || rule.order()>0)) {samples.add(floor+1);roadSurface=true;break;}
                        if(!rule.layered())continue;
                        y=Math.min(y,floor-1); // planning below an existing bridge: ignore its deck and lamps
                    }
                    if(roadSurface)continue;
                    boolean found=false;
                    for(int depth=0;depth<64 && y>=level.getMinBuildHeight();depth++,y--) {
                        BlockPos pos=new BlockPos(column.getX(),y,column.getZ()); var state=level.getBlockState(pos);
                        if(pos.equals(p.controllerPos()) || pos.equals(RoadControllerMigration.target(p)))continue;
                        if(ownImage!=null && RoadCrossingGeometry.body(ownModel).contains(pos.getX(),pos.getZ())) {
                            int row=RoadAxisMath.alongOf(ownModel.axis(),pos);
                            var expected=RoadCrossings.stateAt(ownImage,ownModel,pos);
                            if(y>ownProfile.row(row).baseY() && RoadBlocks.isConcrete(expected) && RoadBlocks.requiredStateMatches(expected,state))continue;
                        }
                        if(!state.getFluidState().isEmpty()) throw new IllegalArgumentException("Water terrain needs manual road planning.");
                        if(state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.getCollisionShape(level,pos).isEmpty()) continue;
                        samples.add(y+1);found=true;break;
                    }
                    if(!found) throw new IllegalArgumentException("No reliable road foundation found; use manual planning.");
                }
                Collections.sort(samples); terrain[i]=samples.get(samples.size()/2);
            }
            int[] raw=terrain.clone();
            for(int i=1;i<n-1;i++) {int[] local={raw[i-1],raw[i],raw[i+1]};Arrays.sort(local);terrain[i]=local[1];}
        }
        if(p.planning().sampleKey().isEmpty()) p.pendingRows().forEach((row,type) -> p.editRow(row,type));
        Map<Integer,CellType> manual=new HashMap<>();
        p.manualRows().forEach((row,type) -> {if(row>=p.minAlong() && row<=p.maxAlong())manual.put(index(p,row),type);});
        int anchor=index(p,RoadHeightProfile.anchorAlong(p));
        var initial=RoadSlopeSolver.solve(terrain,anchor,p.anchorPos().getY(),Map.of(),Set.of(),manual,
                level.getMinBuildHeight()+2,level.getMaxBuildHeight()-10);
        Map<Integer,CellType> base=new TreeMap<>();var initialRows=initial.rows();
        for(int i=0;i<n;i++)if(initialRows[i]!=CellType.FLAT)base.put(along(p,i),initialRows[i]);
        p.setBaseRows(base);p.planning().sample(key,terrain);
        try {balance(level,p,initial,manual);}finally {p.planning().relationshipKey(relations);}
    }
    /** Restore source intent without sampling the now-modified world. */
    public static void restoreBase(ServerLevel level,RoadPlan p) {
        if(!p.hasBase())p.setBaseRows(p.rowOverrides());
        RoadPlan source=baseSource(p);
        Map<Integer,CellType> manual=new HashMap<>();
        p.manualRows().forEach((row,type) -> {if(row>=p.minAlong() && row<=p.maxAlong())manual.put(index(p,row),type);});
        var profile=RoadHeightProfile.of(source);int n=p.length();int[] heights=new int[n];CellType[] rows=new CellType[n];
        for(int i=0;i<n;i++){var row=profile.row(along(p,i));heights[i]=row.entryY();rows[i]=row.type();}
        balance(level,p,new RoadSlopeSolver.Profile(heights,rows),manual);
    }
    public static RoadPlan baseSource(RoadPlan p) {
        RoadPlan source=RoadPlan.load(p.geometrySnapshot());
        source.adoptCommittedRows(p.hasBase() ? p.baseRows() : p.rowOverrides());source.adoptPendingRows(Map.of());
        p.manualRows().forEach((row,type) -> {if(row>=p.minAlong() && row<=p.maxAlong())source.setRowType(row,type);});
        return source;
    }
    private static void balance(ServerLevel level,RoadPlan p,RoadSlopeSolver.Profile initial,Map<Integer,CellType> manual) {
        int n=p.length(),anchor=index(p,RoadHeightProfile.anchorAlong(p));
        // Crossing constraints are collected independently from natural terrain.
        Map<Integer,Integer> pins=new TreeMap<>(),lower=new TreeMap<>(),upper=new TreeMap<>(),bridgePins=new TreeMap<>(); Set<Integer> flat=new HashSet<>();
        for(RoadPlan other:RoadManager.get(level).all()) {
            if(other.id().equals(p.id()) || other.segments().isEmpty() || other.axis()==p.axis()) continue;
            // Built neighbors are hard references. Unbuilt proposals remain editable,
            // but a stable UUID order chooses the reference when both are available.
            RoadPlan applied=RoadBlueprintStore.appliedPlan(level,other);
            RoadPlan reference=RoadCrossings.planningReference(level,p,other);
            var setting=RoadCrossings.setting(level,p,other);
            boolean authorized=RoadCrossingConsent.canPlanAsPlanner(level,p,other);
            if(applied!=null && !RoadCrossings.isScoped(other.id()) && authorized && RoadRules.canEdit(other)) {
                reference=RoadPlan.load(other.geometrySnapshot());reference.pruneOutOfRangeRows();
            }
            var overlap=RoadCrossingGeometry.walk(p).intersect(RoadCrossingGeometry.walk(reference));
            if(overlap==null) continue;

            // Unauthorized pairs default to strict layered avoidance; without a layered rule
            // (edge contact or an explicit same-level approval) they are not constrained here.
            if(!authorized && !setting.layered())continue;
            if(setting.layered()) {
                var span=RoadCrossingGeometry.body(p).intersect(RoadCrossingGeometry.body(reference));
                var refProfile=RoadHeightProfile.of(reference);int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
                for(int row=span.first(reference);row<=span.last(reference);row++) {
                    int base=refProfile.row(row).baseY();lo=Math.min(lo,base);hi=Math.max(hi,base);
                }
                if(setting.order()==0) throw new IllegalArgumentException("Choose which road passes above before automatic clearance balancing.");
                int first=Math.min(index(p,span.first(p)),index(p,span.last(p)));
                int last=Math.max(index(p,span.first(p)),index(p,span.last(p)));
                // Only a pair the planner cannot manage has to clear the neighbour's lamp height:
                // an authorized joint is rebuilt as real geometry, so three blocks plus the
                // normal lamp placeholder is the whole clearance.
                if(!authorized) {
                    if(setting.order()>0)hi=RoadCrossingSafety.upperFloor(hi,RoadCrossingSafety.lampHeight(level,other))-3;
                    else lo=RoadCrossingSafety.lowerFloor(lo,RoadCrossingSafety.lampHeight(level,p))+3;
                }
                RoadBridgeConstraints.add(initial,anchor,first,last,lo,hi,setting.order(),p.planning().spanFloor(other.id()),bridgePins,lower,upper,manual);
                for(var pin:bridgePins.entrySet()){var previous=pins.put(pin.getKey(),pin.getValue());if(previous!=null && !previous.equals(pin.getValue()))throw new IllegalArgumentException("Crossing height constraints conflict.");}
                continue;
            }

            int height;
            try {height=(applied==null || authorized && RoadRules.canEdit(other)) && setting.platform()!=null ? Math.addExact(setting.platform(),1) : RoadCrossingGeometry.platform(reference,overlap)+1;}
            catch(IllegalArgumentException ex) {
                var profile=RoadHeightProfile.of(reference);List<Integer> candidates=new ArrayList<>();
                for(int row=overlap.first(reference);row<=overlap.last(reference);row++) candidates.add(profile.row(row).baseY()+1);
                Collections.sort(candidates);height=candidates.get(candidates.size()/2);
            }
            for(int row=overlap.first(p);row<=overlap.last(p);row++) {
                int i=index(p,row);Integer previous=pins.put(i,height);
                if(previous!=null && previous!=height) throw new IllegalArgumentException("Nearby road platforms require conflicting heights.");
                flat.add(i);
            }
        }
        initial=RoadBridgeConstraints.prefer(initial,anchor,p.anchorPos().getY(),bridgePins);
        var balanced=RoadSlopeSolver.balance(initial,anchor,p.anchorPos().getY(),pins,flat,manual,lower,upper,
                level.getMinBuildHeight()+2,level.getMaxBuildHeight()-10);
        CellType[] rows=balanced.rows();
        // Solver output is authoritative for automatic rows only: a manual row is an input
        // constraint (its type comes back unchanged) and a locked row is a platform pinned by
        // the junction, so writing them again would only be an opportunity to disagree.
        for(int i=0;i<n;i++) p.setAutoType(along(p,i),rows[i]);
        p.planning().issue("");
    }
    private RoadAutoPlanning() { }
}
