/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

import java.util.*;
import com.YLFXM.mcreconstruction.*;
import com.YLFXM.mcreconstruction.network.Network;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Coalesced relationship removal, never edits the world or an active frozen blueprint. */
@EventBusSubscriber(modid=MCReconstruction.MODID)
public final class RoadCrossingUpdates {
    private static final Map<ServerLevel,Set<UUID>> PENDING=new IdentityHashMap<>();
    private static void schedule(ServerLevel level,RoadPlan plan) {
        plan.planning().refreshNeeded(true);RoadManager.get(level).add(plan);
        PENDING.computeIfAbsent(level,key -> new HashSet<>()).add(plan.id());
    }
    public static void removed(ServerLevel level,RoadPlan old) {
        RoadPlan oldModel=old.appliedGeometry()==null ? old : RoadPlan.load(old.appliedGeometry());
        for(var road:RoadManager.get(level).all()) {
            boolean linked=road.planning().crossings().containsKey(old.id());
            if(road.appliedGeometry()!=null) linked|=RoadPlan.load(road.appliedGeometry()).planning().crossings().containsKey(old.id());
            if(!road.segments().isEmpty() && !oldModel.segments().isEmpty())
                linked|=RoadCrossingGeometry.body(road).intersect(RoadCrossingGeometry.body(oldModel))!=null;
            if(linked) {road.planning().remove(old.id());road.planning().externalChanges(true);schedule(level,road);}
        }
    }
    /** A completed shortening can end a relationship while both road plans still exist. */
    public static void reconcileApplied(ServerLevel level,RoadPlan changed) {
        if(changed.appliedGeometry()==null)return;
        RoadPlan actual=RoadPlan.load(changed.appliedGeometry());boolean changedRelations=false;
        for(var road:List.copyOf(RoadManager.get(level).all())) {
            if(road.id().equals(changed.id()) || road.segments().isEmpty())continue;
            RoadPlan reference=RoadBlueprintStore.appliedPlan(level,road);if(reference==null)reference=road;
            boolean linked=actual.planning().crossings().containsKey(road.id()) || reference.planning().crossings().containsKey(changed.id());
            if(linked && RoadCrossingGeometry.body(actual).intersect(RoadCrossingGeometry.body(reference))==null) {
                road.planning().remove(changed.id());road.planning().externalChanges(true);schedule(level,road);
                actual.planning().remove(road.id());changed.planning().remove(road.id());changedRelations=true;
            }
        }
        if(changedRelations){changed.setAppliedGeometry(actual.geometrySnapshot());RoadManager.get(level).add(changed);}
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        PENDING.clear();
        for(ServerLevel level:event.getServer().getAllLevels()) for(var road:RoadManager.get(level).all()) {
            Set<UUID> ids=new HashSet<>(road.planning().crossings().keySet());
            if(road.appliedGeometry()!=null)ids.addAll(RoadPlan.load(road.appliedGeometry()).planning().crossings().keySet());
            boolean missing=false;for(UUID id:ids) if(RoadManager.get(level).get(id)==null) {road.planning().remove(id);missing=true;}
            if(missing || road.planning().refreshNeeded())schedule(level,road);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%20!=0) return;
        for(var entry:List.copyOf(PENDING.entrySet())) {
            var level=entry.getKey();
            for(UUID id:List.copyOf(entry.getValue())) {
                var road=RoadManager.get(level).get(id);
                if(road==null) {entry.getValue().remove(id);continue;}
                if(road.isRepairBusy() || road.state()==RoadState.BUILDING || road.state()==RoadState.REMOVING || RoadCrossings.inUse(level,road))continue;
                if(road.segments().isEmpty()) {road.planning().refreshNeeded(false);entry.getValue().remove(id);continue;}
                RoadPlan proposal=RoadPlan.load(road.save());
                try {
                    // Relationship removal restores structural defaults while preserving the chosen curve.
                    var blueprint=RoadBlueprintGenerator.generate(proposal,level,Config.defaultLampReserveHeight());
                    if(blueprint==null || !RoadBlueprintStore.saveChecked(level,proposal,blueprint)
                            || RoadTemporaryPack.replace(level,road,proposal,blueprint)<0) throw new IllegalArgumentException("Could not regenerate the road proposal.");
                    proposal.planning().refreshNeeded(false);
                    proposal.planning().relationshipKey(RoadAutoPlanning.relationshipKey(level,proposal));
                    if(proposal.state()!=RoadState.DRAFT)proposal.setState(RoadState.PLANNED);
                    RoadManager.get(level).add(proposal);entry.getValue().remove(id);
                    notify(level,proposal,"mcreconstruction.crossing.regenerated");
                } catch(RuntimeException ex) {
                    road.planning().refreshNeeded(false);road.planning().issue(ex.getMessage()==null ? "Road regeneration failed" : ex.getMessage());
                    RoadManager.get(level).add(road);entry.getValue().remove(id);
                    notify(level,road,"mcreconstruction.crossing.regeneration_failed");
                    MCReconstruction.LOGGER.warn("Crossing proposal regeneration failed for {}",id,ex);
                }
            }
            if(entry.getValue().isEmpty())PENDING.remove(level);
            level.players().forEach(Network::sendRoads);
        }
    }
    private static void notify(ServerLevel level,RoadPlan road,String key) {
        for(var player:level.players())if(RoadPermissions.canManage(level,player,road))
            player.displayClientMessage(Component.translatable(key,RoadNames.label(road)),false);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {PENDING.clear();}
    private RoadCrossingUpdates() { }
}
