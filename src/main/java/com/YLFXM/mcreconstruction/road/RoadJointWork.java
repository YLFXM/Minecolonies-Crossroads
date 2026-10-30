/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import com.YLFXM.mcreconstruction.*;
import com.ldtteam.structurize.blueprints.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;

/** Frozen local changes on neighboring roads, executed as one masked native work volume. */
public final class RoadJointWork {
    public record Prepared(Blueprint work,Blueprint primary,CompoundTag updates,List<BlockPos> clearTargets) { }
    record Cell(BlockState state,CompoundTag tile) { }
    private record Images(Map<UUID,Blueprint> raw,Map<UUID,Blueprint> targets) { }
    private record Targets(Map<UUID,RoadPlan> models,Map<UUID,Blueprint> old,Set<UUID> proposalOnly,Set<UUID> readOnly) { }
    /** The same prospective pair targets feed ordinary ghosts, joint ghosts and native freezing. */
    private static Targets targets(ServerLevel level,RoadPlan primary,boolean strict) {
        Map<UUID,RoadPlan> models=new LinkedHashMap<>();models.put(primary.id(),primary);
        Map<UUID,Blueprint> old=new HashMap<>();Set<UUID> proposalOnly=new HashSet<>(),readOnly=new HashSet<>();
        for(var live:RoadManager.get(level).all().stream().sorted(Comparator.comparing(RoadPlan::id)).toList()) {
            if(live.id().equals(primary.id()) || !live.dimension().equals(primary.dimension()) || live.segments().isEmpty())continue;
            if(!potentialOverlap(primary,live))continue;
            var actual=RoadBlueprintStore.appliedPlan(level,live);
            boolean candidate=RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(live))!=null;
            boolean physical=actual!=null && RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(actual))!=null;
            boolean authorized=RoadCrossingConsent.canPlan(level,primary,live);
            boolean restoration=!authorized && actual!=null && RoadCrossingRestoration.geometry(level,primary,live);
            if(!candidate && !physical || !authorized && !restoration)continue;
            if(live.isRepairBusy() || live.hasInterruptedConstruction() || live.state()==RoadState.BUILDING || live.state()==RoadState.REMOVING || RoadCrossings.inUse(level,live)) {
                if(strict)throw new IllegalArgumentException("A related road cannot be changed now: "+RoadNames.label(live));
                continue;
            }
            if(restoration) {
                // Read-only applied geometry supplies the original shared stairs/kerbs.
                // No neighbor update is emitted; the assembled work still needs the full restoration proof.
                models.put(live.id(),RoadPlan.load(actual.geometrySnapshot()));readOnly.add(live.id());
                old.put(live.id(),RoadBlueprintStore.loadApplied(level,live));continue;
            }
            var target=prospectivePeer(live,actual,primary);var rule=RoadCrossings.setting(primary,live);
            if(!target.planning().hasBase())target.planning().baseRows(target.rowOverrides());
            target.planning().crossing(primary.id(),new RoadPlanningState.Crossing(rule.layered(),-rule.order(),rule.version(),rule.platform()));
            models.put(target.id(),target);old.put(target.id(),actual==null ? null:RoadBlueprintStore.loadApplied(level,live));
            // An extension that has not been built is a proposal, not a license to construct that entire peer.
            if(actual==null || !physical)proposalOnly.add(target.id());
            try(var scope=RoadCrossings.scope(models,Map.of(),primary.id())) {
                if(!rule.layered() && (proposalOnly.contains(target.id()) || rule.platform()!=null && RoadRules.canChangePlatform(primary,live)))
                    alignUnbuilt(level,primary,target,rule.platform());
                else reshapeNeighbor(level,primary,target,rule);
                if(actual!=null && !RoadRules.canEdit(live) && !target.rowOverrides().equals(actual.rowOverrides()))
                    throw new IllegalArgumentException("Open blueprint editing on the existing road before changing its junction height.");
            } catch(IllegalArgumentException ex) {
                if(strict)throw ex;
                primary.planning().issue(ex.getMessage());
            }
        }
        return new Targets(models,old,proposalOnly,readOnly);
    }
    public static boolean potentialOverlap(RoadPlan own,RoadPlan peer) {
        var body=RoadCrossingGeometry.body(own);
        if(body.intersect(RoadCrossingGeometry.body(peer))!=null)return true;
        var bounds=peer.appliedBounds();
        if(bounds!=null && body.minX()<=bounds.maxX() && body.maxX()>=bounds.minX() && body.minZ()<=bounds.maxZ() && body.maxZ()>=bounds.minZ())return true;
        return peer.appliedGeometry()!=null && body.intersect(RoadCrossingGeometry.body(RoadPlan.load(peer.appliedGeometry())))!=null;
    }
    static RoadPlan prospectivePeer(RoadPlan live,RoadPlan applied,RoadPlan primary) {
        var target=peerTarget(live,applied,primary);
        if(applied!=null && RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(applied))==null
                && RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(live))!=null) {
            // Preview the current extension, but carry only actual source rows; unrelated pending edits remain on live.
            target=RoadPlan.load(live.geometrySnapshot());target.adoptCommittedRows(applied.rowOverrides());target.adoptPendingRows(Map.of());
        }
        target.planning().planner(primary.planning().planner());return target;
    }
    public static Blueprint singlePreview(ServerLevel level,RoadPlan proposed,int reserve) {
        var primary=RoadPlan.load(proposed.geometrySnapshot());primary.applyPendingRows();
        var resolved=targets(level,primary,false);var images=images(level,resolved.models(),primary.id(),reserve,false);
        proposed.planning().crossingIssue(primary.planning().crossingIssue());
        var display=new LinkedHashMap<>(resolved.models());resolved.readOnly().forEach(display::remove);
        return ghostProjection(primary,display,images.raw(),images.targets(),level.registryAccess());
    }
    static Blueprint ghostProjection(RoadPlan primary,Map<UUID,RoadPlan> models,Map<UUID,Blueprint> raw,Map<UUID,Blueprint> targets,net.minecraft.core.HolderLookup.Provider registries) {
        var nativeImage=targets.get(primary.id());var origin=RoadBlueprintOps.origin(primary,nativeImage);
        var ghost=RoadBlueprintGenerator.emptyBlueprint((short)nativeImage.getSizeX(),(short)nativeImage.getSizeY(),(short)nativeImage.getSizeZ(),registries);
        Map<BlockPos,Cell> visible=new HashMap<>();
        for(var model:models.values()) {
            if(!model.id().equals(primary.id()) && RoadCrossings.setting(primary,model).layered())continue;
            var image=targets.get(model.id());var base=RoadBlueprintOps.origin(model,image);
            for(var info:image.getBlockInfoAsList()) {
                var world=base.offset(info.getPos());var local=world.subtract(origin);
                if(!RoadCrossings.inside(ghost,local) || !RoadCrossingGeometry.body(primary).contains(world.getX(),world.getZ()))continue;
                var cell=visibleCell(image,raw.get(model.id()),model,info.getPos());
                if(!skip(cell.state()) && !cell.state().isAir())putPreview(visible,world,cell);
            }
        }
        for(var e:visible.entrySet())writeCell(ghost,e.getKey().subtract(origin),e.getValue());
        ghost.setCachePrimaryOffset(primary.anchorPos().subtract(origin));return ghost;
    }
    private static Images images(ServerLevel level,Map<UUID,RoadPlan> models,UUID operation,int reserve,boolean validate) {
        Map<UUID,Blueprint> raw=new LinkedHashMap<>(),result=new LinkedHashMap<>();
        try(var scope=RoadCrossings.scope(models,Map.of(),operation)) {
            for(var model:models.values())raw.put(model.id(),RoadBlueprintGenerator.raw(model,level,reserve));
        }
        try(var scope=RoadCrossings.scope(models,raw,operation)) {
            for(var model:models.values()) {
                if(validate)RoadCrossings.validateGeometry(level,model);
                var image=copy(raw.get(model.id()),level);RoadCrossings.decorate(level,model,image);result.put(model.id(),image);
            }
        }
        var roads=new ArrayList<>(models.values());
        for(int i=0;i<roads.size();i++)for(int j=i+1;j<roads.size();j++) {
            var a=roads.get(i);var b=roads.get(j);if(RoadCrossings.setting(a,b).layered())continue;
            var overlap=RoadCrossingGeometry.body(a).intersect(RoadCrossingGeometry.body(b));if(overlap==null)continue;
            var ai=result.get(a.id());var bi=result.get(b.id());var ao=RoadBlueprintOps.origin(a,ai);var bo=RoadBlueprintOps.origin(b,bi);
            for(var info:ai.getBlockInfoAsList()) {
                var world=ao.offset(info.getPos());var bp=world.subtract(bo);
                if(!overlap.contains(world.getX(),world.getZ()) || !RoadCrossings.inside(bi,bp))continue;
                var ac=cell(ai,info.getPos());var bc=cell(bi,bp);
                if(skip(ac.state()) || skip(bc.state())) {
                    // A placeholder written by neighbour deference is not a decision. When both roads
                    // really carry content here, the junction places it once and both targets agree.
                    var ra=cell(raw.get(a.id()),info.getPos());var rb=cell(raw.get(b.id()),bp);
                    if(!skip(ra.state()) && !skip(rb.state()) && !ra.state().isAir() && !rb.state().isAir()) {
                        try {var resolved=sharedCell(ra,rb);writeCell(ai,info.getPos(),resolved);writeCell(bi,bp,resolved);}
                        catch(IllegalArgumentException ignored) { }
                    }
                    continue;
                }
                try {var shared=sharedCell(ac,bc);writeCell(ai,info.getPos(),shared);writeCell(bi,bp,shared);}
                catch(IllegalArgumentException ex) {
                    String message=ex.getMessage()+" at "+world.toShortString();
                    if(validate)throw new IllegalArgumentException(message);
                    a.planning().crossingIssue(message);b.planning().crossingIssue(message);
                }
            }
            ai.setCachePrimaryOffset(a.anchorPos().subtract(ao));bi.setCachePrimaryOffset(b.anchorPos().subtract(bo));
        }
        if(validate)try(var scope=RoadCrossings.scope(models,result,operation)) {
            for(var model:models.values())RoadCrossings.validateClearance(level,model,result.get(model.id()));
        }
        return new Images(raw,result);
    }
    static Cell sharedCell(Cell a,Cell b) {
        if(equal(a,b))return a;
        if(RoadBlueprintOps.isStair(a.state()) && !RoadBlueprintOps.isStair(b.state()))return a;
        if(RoadBlueprintOps.isStair(b.state()) && !RoadBlueprintOps.isStair(a.state()))return b;
        if(a.state().isAir())return b;if(b.state().isAir())return a;
        if(RoadBlueprintOps.isSolidFill(a.state()) && RoadBlueprintOps.isConcrete(b.state()))return b;
        if(RoadBlueprintOps.isSolidFill(b.state()) && RoadBlueprintOps.isConcrete(a.state()))return a;
        throw new IllegalArgumentException("Conflicting native work targets: "+a.state()+" / "+b.state());
    }
    private static Cell visibleCell(Blueprint image,Blueprint raw,RoadPlan model,BlockPos local) {
        var cell=cell(image,local);
        if(skip(cell.state())) {var source=cell(raw,local);var world=RoadBlueprintOps.origin(model,image).offset(local);
            if(RoadBlueprintOps.isSolidFill(source.state()) && RoadCrossings.deckCell(model,world))return source;}
        return cell;
    }
    private static void writeCell(Blueprint image,BlockPos local,Cell cell) {
        image.addBlockState(local,cell.state());var tile=cell.tile()==null ? null:cell.tile().copy();
        if(tile!=null){tile.putInt("x",local.getX());tile.putInt("y",local.getY());tile.putInt("z",local.getZ());}
        image.getTileEntities()[local.getY()][local.getZ()][local.getX()]=tile;
    }
    /** Pair ghosts contain complete visible proposals, never a native work-order snapshot. */
    public static Blueprint preview(ServerLevel level,ServerPlayer player,RoadPlan proposed,UUID peerId) {
        RoadPlan live=RoadManager.get(level).get(peerId);
        if(live==null || !RoadCrossingConsent.canPlan(level,player,proposed,live) || live.segments().isEmpty())throw new IllegalArgumentException("The preview road is unavailable.");
        RoadPlan primary=RoadPlan.load(proposed.geometrySnapshot());primary.applyPendingRows();
        var resolved=targets(level,primary,false);RoadPlan peer=resolved.models().get(peerId);
        if(peer==null)throw new IllegalArgumentException("The preview road is busy or unavailable.");
        if(RoadCrossingGeometry.body(primary).intersect(RoadCrossingGeometry.body(peer))==null)throw new IllegalArgumentException("The selected roads no longer overlap.");
        var generated=images(level,resolved.models(),primary.id(),Config.defaultLampReserveHeight(),false);var finalImages=generated.targets();
        Map<BlockPos,Cell> cells=new HashMap<>();
        for(var model:List.of(primary,peer)) {
            var image=finalImages.get(model.id());var origin=RoadBlueprintOps.origin(model,image);
            for(var info:image.getBlockInfoAsList()) {
                var pos=origin.offset(info.getPos());
                if(model.id().equals(peer.id()) && !RoadPermissions.canManage(level,player,live) && !RoadCrossingGeometry.body(primary).contains(pos.getX(),pos.getZ()))continue;
                Cell visible=visibleCell(image,generated.raw().get(model.id()),model,info.getPos());
                if(!skip(visible.state()) && !visible.state().isAir())putPreview(cells,pos,visible);
            }
        }
        return volume(level,primary,cells,true);
    }
    public static Prepared prepare(ServerLevel level,ServerPlayer player,RoadPlan primary) {
        var authorized=RoadCrossingConsent.prepare(level,player,primary);
        if(authorized==null)return null;
        return authorized.work();
    }
    /** Server-only proposal construction. Caller must obtain scoped consent before any publish or dispatch. */
    static Prepared propose(ServerLevel level,ServerPlayer player,RoadPlan primary,boolean preserveForeign) {
        var resolved=targets(level,primary,true);
        var models=resolved.models();var old=resolved.old();
        var targets=images(level,models,primary.id(),Config.defaultLampReserveHeight(),true).targets();
        Map<BlockPos,Cell> work=new HashMap<>();List<BlockPos> clears=new ArrayList<>();
        Blueprint primaryTarget=targets.get(primary.id());addWhole(work,primary,primaryTarget);
        clears.addAll(RoadBlueprintOps.clearTargets(primary,RoadBlueprintStore.loadApplied(level,primary),primaryTarget));
        CompoundTag updates=new CompoundTag();
        for(var entry:models.entrySet()) {
            if(entry.getKey().equals(primary.id()) || resolved.readOnly().contains(entry.getKey()))continue;
            RoadPlan model=entry.getValue();Blueprint next=targets.get(model.id()),before=old.get(model.id());
            if(before==null || resolved.proposalOnly().contains(model.id())) {
                CompoundTag data=new CompoundTag();data.put("model",model.geometrySnapshot());data.put("blueprint",BlueprintUtil.writeBlueprintToNBT(next));data.putBoolean("proposalOnly",true);data.putBoolean("existingProposal",before!=null);
                updates.put(model.id().toString(),data);continue;
            }
            BlockPos origin=RoadBlueprintOps.origin(model,next);int changed=0;List<BlockPos> changedPositions=new ArrayList<>();
            for(var info:next.getBlockInfoAsList()) {
                BlockPos world=origin.offset(info.getPos());if(skip(info.getState()))continue;
                Cell after=cell(next,info.getPos());Cell previous=cellAt(before,model,world);
                if(equal(after,previous))continue;
                put(work,world,after);changed++;changedPositions.add(world);
                if(after.state().isAir() && RoadBlueprintOps.isConcrete(previous.state()))clears.add(world);
            }
            if(changed>0 || !model.rowOverrides().equals(RoadPlan.load(RoadManager.get(level).get(model.id()).geometrySnapshot()).rowOverrides())) {
                CompoundTag data=new CompoundTag();data.put("model",model.geometrySnapshot());data.put("blueprint",BlueprintUtil.writeBlueprintToNBT(next));
                data.putInt("changed",changed);
                if(!changedPositions.isEmpty()) {
                    data.putIntArray("min",new int[]{changedPositions.stream().mapToInt(BlockPos::getX).min().orElse(0),changedPositions.stream().mapToInt(BlockPos::getY).min().orElse(0),changedPositions.stream().mapToInt(BlockPos::getZ).min().orElse(0)});
                    data.putIntArray("max",new int[]{changedPositions.stream().mapToInt(BlockPos::getX).max().orElse(0),changedPositions.stream().mapToInt(BlockPos::getY).max().orElse(0),changedPositions.stream().mapToInt(BlockPos::getZ).max().orElse(0)});
                }
                updates.put(model.id().toString(),data);
            }
        }
            if(preserveForeign) {
            for(var n:RoadCrossings.neighbors(level,primary))if(!RoadCrossingConsent.canPlan(level,player,primary,n.live()) && n.applied()!=null) {
                if(primary.planning().policy(n.live().id())!=2 && RoadCrossingRestoration.geometry(level,primary,n.live()))continue;
                var origin=RoadBlueprintOps.origin(n.model(),n.applied());var ownOrigin=RoadBlueprintOps.origin(primary,primaryTarget);
                for(var info:n.applied().getBlockInfoAsList())if(RoadBlueprintOps.isConcrete(info.getState()) || RoadBlueprintOps.isSolidFill(info.getState())) {
                    var pos=origin.offset(info.getPos());work.remove(pos);
                    RoadCrossings.write(primaryTarget,pos.subtract(ownOrigin),com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState());
                }
                primaryTarget.setCachePrimaryOffset(primary.anchorPos().subtract(ownOrigin));
            }
        }
        for(var entry:work.entrySet()) {
            if(!level.hasChunkAt(entry.getKey()))throw new IllegalArgumentException("Load the entire joint work area before construction.");
            if(entry.getValue().state().isAir() && !level.getBlockState(entry.getKey()).isAir())clears.add(entry.getKey());
        }
        return new Prepared(volume(level,primary,work),primaryTarget,updates,clears.stream().distinct()
                .filter(pos -> work.containsKey(pos) && work.get(pos).state().isAir()).toList());
    }
    public static void alignUnbuilt(ServerLevel level,RoadPlan primary,RoadPlan peer,Integer requestedFloor) {
        alignTargets(primary,peer,requestedFloor,level.getMinBuildHeight()+2,level.getMaxBuildHeight()-10);
    }
    /** Solve both selected new targets together, with every other recorded junction pinned to its lock. */
    static void alignTargets(RoadPlan primary,RoadPlan peer,Integer requestedFloor,int min,int max) {
        var intersection=RoadCrossingGeometry.walk(primary).intersect(RoadCrossingGeometry.walk(peer));
        if(intersection==null)return;
        var profile=RoadHeightProfile.of(primary);int start=requestedFloor==null ? profile.row(intersection.first(primary)).baseY()+1 : Math.addExact(requestedFloor,1);
        int search=requestedFloor==null ? Math.min(primary.length(),peer.length()) : 0;
        for(int distance=0;distance<=search;distance++)for(int sign:new int[]{1,-1}) {
            if(distance==0 && sign==-1)continue;int height=start+distance*sign;
            try {
                var a=RoadPlan.load(primary.geometrySnapshot());var b=RoadPlan.load(peer.geometrySnapshot());
                flattenAt(a,intersection,height,min,max);flattenAt(b,intersection,height,min,max);
                primary.adoptCommittedRows(a.rowOverrides());primary.adoptPendingRows(Map.of());
                peer.adoptCommittedRows(b.rowOverrides());peer.adoptPendingRows(Map.of());return;
            } catch(IllegalArgumentException ex) {if(ex.getMessage()!=null && ex.getMessage().contains("budget"))throw ex;}
        }
        throw new IllegalArgumentException("The two selected roads have no common reachable junction height.");
    }
    static RoadPlan peerTarget(RoadPlan live,RoadPlan applied,RoadPlan primary) {
        RoadPlan result=RoadPlan.load((applied==null ? live:applied).geometrySnapshot());
        if(applied==null)result.applyPendingRows();else {
            result.adoptPendingRows(Map.of());
            var old=applied.planning().crossings().getOrDefault(primary.id(),new RoadPlanningState.Crossing(false,0));var next=RoadCrossings.setting(primary,live);
            if(RoadRules.canEdit(live) && (old.layered()!=next.layered() || old.order()!=-next.order())) {
                result=RoadAutoPlanning.baseSource(result);result.applyPendingRows();
            }
        }
        result.planning().planner(primary.planning().planner());return result;
    }
    private static void flattenAt(RoadPlan road,RoadCrossingGeometry.Rect intersection,int height,int min,int max) {
        var profile=RoadHeightProfile.of(road);int n=road.length();int[] heights=new int[n];CellType[] rows=new CellType[n];
        for(int i=0;i<n;i++){var row=profile.row(RoadAutoPlanning.along(road,i));heights[i]=row.entryY();rows[i]=row.type();}
        Map<Integer,Integer> pins=new HashMap<>();Set<Integer> flat=new HashSet<>();Map<Integer,CellType> manual=new HashMap<>();
        for(int row=intersection.first(road);row<=intersection.last(road);row++){int i=RoadAutoPlanning.index(road,row);pins.put(i,height);flat.add(i);}
        // Every other recorded same-level junction stays where its lock says; unrecorded roads are
        // not consulted at all, so a road that merely passes nearby cannot block this operation.
        for(var lock:road.planning().locks().entrySet()) {
            int row=lock.getKey();if(lock.getValue().anchor())continue;
            if(row>=intersection.first(road) && row<=intersection.last(road))continue;
            int i=RoadAutoPlanning.index(road,row);Integer previous=pins.put(i,lock.getValue().height());flat.add(i);
            if(previous!=null && previous!=lock.getValue().height())throw new IllegalArgumentException("An existing junction fixes a different height.");
        }
        road.planning().manual().forEach((row,type) -> {if(row>=road.minAlong() && row<=road.maxAlong())manual.put(RoadAutoPlanning.index(road,row),type);});
        var solved=RoadSlopeSolver.balance(new RoadSlopeSolver.Profile(heights,rows),RoadAutoPlanning.index(road,RoadHeightProfile.anchorAlong(road)),road.anchorPos().getY(),pins,flat,manual,min,max);
        var types=solved.rows();for(int i=0;i<n;i++)road.setRowType(RoadAutoPlanning.along(road,i),types[i]);road.applyPendingRows();
    }
    private static void reshapeNeighbor(ServerLevel level,RoadPlan primary,RoadPlan peer,RoadPlanningState.Crossing setting) {
        var overlap=RoadCrossingGeometry.walk(primary).intersect(RoadCrossingGeometry.walk(peer));if(overlap==null)return;
        var profile=RoadHeightProfile.of(peer);int n=peer.length();int[] heights=new int[n];CellType[] rows=new CellType[n];
        for(int i=0;i<n;i++) {var row=profile.row(RoadAutoPlanning.along(peer,i));heights[i]=row.entryY();rows[i]=row.type();}
        Map<Integer,Integer> pins=new HashMap<>();Set<Integer> flat=new HashSet<>();
        if(!setting.layered()) {
            int height=RoadCrossingGeometry.platform(primary,overlap)+1;
            for(int along=overlap.first(peer);along<=overlap.last(peer);along++) {int i=RoadAutoPlanning.index(peer,along);pins.put(i,height);flat.add(i);}
            for(var lock:peer.planning().locks().entrySet()) {
                int along=lock.getKey();if(lock.getValue().anchor())continue;
                if(along>=overlap.first(peer) && along<=overlap.last(peer))continue;
                int i=RoadAutoPlanning.index(peer,along);Integer previous=pins.put(i,lock.getValue().height());flat.add(i);
                if(previous!=null && previous!=lock.getValue().height())throw new IllegalArgumentException("The approach is constrained by another junction height.");
            }
        }
        if(pins.isEmpty())return;
        Map<Integer,CellType> manual=new HashMap<>();peer.planning().manual().forEach((along,type) -> {
            if(along>=peer.minAlong() && along<=peer.maxAlong())manual.put(RoadAutoPlanning.index(peer,along),type);
        });
        var result=RoadSlopeSolver.balance(new RoadSlopeSolver.Profile(heights,rows),RoadAutoPlanning.index(peer,RoadHeightProfile.anchorAlong(peer)),
                peer.anchorPos().getY(),pins,flat,manual,level.getMinBuildHeight()+2,level.getMaxBuildHeight()-10);
        var types=result.rows();for(int i=0;i<n;i++)peer.setRowType(RoadAutoPlanning.along(peer,i),types[i]);peer.applyPendingRows();
    }
    public static void validateFrozen(ServerLevel level,ServerPlayer player,RoadRepairSession session) {
        Map<UUID,RoadPlan> models=new HashMap<>();models.put(session.target().id(),session.target());Map<UUID,Blueprint> images=new HashMap<>();
        var updates=session.neighborUpdates();java.util.Set<UUID> applied=new java.util.HashSet<>();
        for(String key:updates.getAllKeys()) {
            var data=updates.getCompound(key);var model=RoadPlan.load(data.getCompound("model"));
            models.put(model.id(),model);images.put(model.id(),BlueprintUtil.readBlueprintFromNBT(data.getCompound("blueprint"),level.registryAccess()));
        }
        if(!session.primaryBlueprint().isEmpty())images.put(session.target().id(),BlueprintUtil.readBlueprintFromNBT(session.primaryBlueprint(),level.registryAccess()));
        RoadCrossingConsent.validate(level,session.owner(),session);
        try(var scope=RoadCrossings.scope(models,images,session.target().id())) {
            for(var model:models.values()) {RoadCrossings.validateGeometry(level,model);if(images.containsKey(model.id()))RoadCrossings.validateClearance(level,model,images.get(model.id()));}
        }
    }
    static void stageProposalRows(RoadPlan live,RoadPlan target,RoadPlan actual) {
        for(int row=live.minAlong();row<=live.maxAlong();row++)
            if(actual==null || target.cellType(row)!=actual.cellType(row))live.setRowType(row,target.cellType(row));
    }
    public static void commitNeighborRows(RoadPlan live,RoadPlan actual) {
        Map<Integer,CellType> pending=new HashMap<>(live.pendingRows());
        if(!live.planning().hasBase())pending.forEach((row,type) -> {if(!live.planning().manual().containsKey(row))live.planning().manual(row,type);});
        live.adoptCommittedRows(actual.rowOverrides());live.adoptPendingRows(Map.of());pending.forEach(live::setRowType);
    }
    /** Idempotent redo: the complete frozen transaction is flushed before replacing any snapshot. */
    public static boolean commit(ServerLevel level,RoadPlan live,RoadRepairSession session,Blueprint fallback) {
        if(!session.committing()) {session.markCommitting();RoadManager.get(level).add(live);}
        try {RoadJointJournal.prepare(level,live,session);}
        catch(java.io.IOException ex) {MCReconstruction.LOGGER.error("Could not persist joint commit {}",live.id(),ex);return false;}
        var updates=session.neighborUpdates();java.util.Set<UUID> applied=new java.util.HashSet<>();
        for(String key:updates.getAllKeys()) {
            var peer=RoadManager.get(level).get(UUID.fromString(key));if(peer==null)return false;
            var data=updates.getCompound(key);RoadPlan model=RoadPlan.load(data.getCompound("model"));
            Blueprint target=BlueprintUtil.readBlueprintFromNBT(data.getCompound("blueprint"),level.registryAccess());
            if(data.getBoolean("proposalOnly")) {
                RoadPlan baseline=data.getBoolean("existingProposal") ? RoadBlueprintStore.appliedPlan(level,peer):null;
                stageProposalRows(peer,model,baseline);
                if(!RoadBlueprintStore.saveChecked(level,peer,target) || RoadTemporaryPack.replace(level,peer,peer,target)<0)return false;
            } else {
                if(!RoadBlueprintStore.saveAppliedChecked(level,peer,target,model))return false;
                commitNeighborRows(peer,model);applied.add(peer.id());
            }
            if(!peer.planning().hasBase())peer.planning().baseRows(model.planning().baseRows());
            model.planning().crossings().forEach((id,rule) -> {
                var previous=peer.planning().crossings().get(id);
                if(previous==null || previous.version()<=rule.version())peer.planning().crossing(id,rule);
            });
            RoadManager.get(level).add(peer);
        }
        Blueprint primary=session.primaryBlueprint().isEmpty() ? fallback : BlueprintUtil.readBlueprintFromNBT(session.primaryBlueprint(),level.registryAccess());
        boolean committed=RoadBlueprintStore.saveAppliedChecked(level,live,primary,session.target()) && RoadBlueprintStore.saveChecked(level,live,primary);
        if(committed)RoadCrossingConsent.settle(level,live,applied);
        return committed;
    }
    public static List<String> impact(ServerLevel level,RoadRepairSession session) {
        if(session==null)return List.of();List<String> lines=new ArrayList<>();var updates=session.neighborUpdates();
        if(session.target().planning().externalChanges())lines.add("Released structures may be cleared or overwritten; inspect the preview.");
        for(String key:updates.getAllKeys().stream().sorted().toList()) {
            var data=updates.getCompound(key);var model=RoadPlan.load(data.getCompound("model"));
            lines.add(RoadNames.label(model)+" ("+data.getInt("changed")+")");
            int[] min=data.getIntArray("min"),max=data.getIntArray("max");
            if(min.length==3 && max.length==3) {lines.add("X "+min[0]+".."+max[0]+"  Z "+min[2]+".."+max[2]);lines.add("Y "+min[1]+".."+max[1]);}
        }
        return List.copyOf(lines);
    }
    private static void addWhole(Map<BlockPos,Cell> cells,RoadPlan model,Blueprint blueprint) {
        var origin=RoadBlueprintOps.origin(model,blueprint);
        for(var info:blueprint.getBlockInfoAsList())if(!skip(info.getState()))put(cells,origin.offset(info.getPos()),cell(blueprint,info.getPos()));
    }
    /** Demolition may include only proven partial primary cells; unfinished peer work is retained. */
    public static Blueprint interruptedRemoval(ServerLevel level,RoadPlan live,Blueprint applied) {
        if(live.interruptedConstruction()==null)return applied;
        var session=RoadRepairSession.load(live.interruptedConstruction());
        if(session.primaryBlueprint().isEmpty())return applied;
        var proposed=BlueprintUtil.readBlueprintFromNBT(session.primaryBlueprint(),level.registryAccess());
        Map<BlockPos,Cell> cells=new HashMap<>();
        if(applied!=null) {
            var origin=RoadBlueprintOps.origin(live,applied);
            for(var info:applied.getBlockInfoAsList())if(RoadBlueprintOps.isConcrete(info.getState()))cells.put(origin.offset(info.getPos()),cell(applied,info.getPos()));
        }
        var origin=RoadBlueprintOps.origin(session.target(),proposed);
        for(var info:proposed.getBlockInfoAsList()) {
            if(!RoadBlueprintOps.isConcrete(info.getState()))continue;var pos=origin.offset(info.getPos());
            if(RoadBlueprintOps.requiredStateMatches(info.getState(),level.getBlockState(pos)))cells.put(pos,cell(proposed,info.getPos()));
        }
        var result=volume(level,live,cells);var resultOrigin=RoadBlueprintOps.origin(live,result);var updates=session.neighborUpdates();
        for(String key:updates.getAllKeys()) {
            var data=updates.getCompound(key);var peer=RoadPlan.load(data.getCompound("model"));
            var image=BlueprintUtil.readBlueprintFromNBT(data.getCompound("blueprint"),level.registryAccess());
            for(var info:result.getBlockInfoAsList()) {
                var pos=resultOrigin.offset(info.getPos());
                if(RoadBlueprintOps.isConcrete(RoadCrossings.stateAt(image,peer,pos)))RoadCrossings.write(result,info.getPos(),com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState());
            }
        }
        result.setCachePrimaryOffset(live.anchorPos().subtract(resultOrigin));return result;
    }
    private static boolean skip(BlockState state) {return state.is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get());}
    private static Cell cell(Blueprint b,BlockPos p) {
        CompoundTag tile=b.getTileEntities()[p.getY()][p.getZ()][p.getX()];
        if(tile!=null) {tile=tile.copy();tile.remove("x");tile.remove("y");tile.remove("z");}
        return new Cell(b.getBlockState(p),tile);
    }
    private static Cell cellAt(Blueprint b,RoadPlan model,BlockPos world) {
        var local=world.subtract(RoadBlueprintOps.origin(model,b));
        return RoadCrossings.inside(b,local) ? cell(b,local) : new Cell(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),null);
    }
    private static boolean equal(Cell a,Cell b) {return a.state().equals(b.state()) && Objects.equals(a.tile(),b.tile());}
    private static void putPreview(Map<BlockPos,Cell> cells,BlockPos pos,Cell next) {
        Cell previous=cells.get(pos);
        if(previous==null || RoadBlueprintOps.isSolidFill(previous.state()) && !RoadBlueprintOps.isSolidFill(next.state())
                || !RoadBlueprintOps.isSolidFill(next.state()) && RoadBlueprintOps.isStair(next.state()) && !RoadBlueprintOps.isStair(previous.state()))cells.put(pos.immutable(),next);
    }
    private static void put(Map<BlockPos,Cell> cells,BlockPos pos,Cell next) {
        Cell previous=cells.get(pos);
        if(previous==null || equal(previous,next)) {cells.put(pos.immutable(),next);return;}
        if(RoadBlueprintOps.isSolidFill(previous.state()) && RoadBlueprintOps.isConcrete(next.state())) {cells.put(pos.immutable(),next);return;}
        if(RoadBlueprintOps.isSolidFill(next.state()) && RoadBlueprintOps.isConcrete(previous.state()))return;
        if(previous.state().isAir() && RoadBlueprintOps.isConcrete(next.state())) {cells.put(pos.immutable(),next);return;}
        if(next.state().isAir() && RoadBlueprintOps.isConcrete(previous.state()))return;
        throw new IllegalArgumentException("Conflicting native work targets at "+pos.toShortString());
    }
    private static Blueprint volume(ServerLevel level,RoadPlan primary,Map<BlockPos,Cell> cells) {
        return volume(level,primary,cells,false);
    }
    private static Blueprint volume(ServerLevel level,RoadPlan primary,Map<BlockPos,Cell> cells,boolean preview) {
        // Include the primary anchor and reserve even when they are protected no-ops.
        int minX=primary.anchorPos().getX(),minY=primary.anchorPos().getY(),minZ=primary.anchorPos().getZ();
        int maxX=minX,maxY=minY,maxZ=minZ;
        for(var p:cells.keySet()) {minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
        int sx=maxX-minX+1,sy=maxY-minY+1,sz=maxZ-minZ+1;
        if(sx>Short.MAX_VALUE || sy>Short.MAX_VALUE || sz>Short.MAX_VALUE || (long)sx*sy*sz>Config.maxGeneratedBlueprintVolume())
            throw new IllegalArgumentException("The joint native work volume exceeds the permitted size.");
        Blueprint work=preview ? RoadBlueprintGenerator.emptyBlueprint((short)sx,(short)sy,(short)sz,level.registryAccess())
                : new Blueprint((short)sx,(short)sy,(short)sz,level.registryAccess()); // Native work holes are protected; ghost holes are AIR.
        BlockPos origin=new BlockPos(minX,minY,minZ);
        for(var e:cells.entrySet()) {
            BlockPos p=e.getKey().subtract(origin);work.addBlockState(p,e.getValue().state());
            if(e.getValue().tile()!=null) {CompoundTag tile=e.getValue().tile().copy();tile.putInt("x",p.getX());tile.putInt("y",p.getY());tile.putInt("z",p.getZ());work.getTileEntities()[p.getY()][p.getZ()][p.getX()]=tile;}
        }
        work.setCachePrimaryOffset(primary.anchorPos().subtract(origin));work.setFileName(RoadNames.fileName(primary));return work;
    }
    private static Blueprint copy(Blueprint b,ServerLevel level) {return BlueprintUtil.readBlueprintFromNBT(BlueprintUtil.writeBlueprintToNBT(b),level.registryAccess());}
    private RoadJointWork() { }
}
