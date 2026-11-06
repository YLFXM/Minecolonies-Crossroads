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
import com.YLFXM.rp.crossroads.*;
import com.YLFXM.rp.crossroads.network.Network;
import com.YLFXM.rp.crossroads.road.common.RoadProposal;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Coalesced relationship removal, never edits the world or an active frozen blueprint. */
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadCrossingUpdates {
    private static final Map<ServerLevel,Set<UUID>> PENDING=new IdentityHashMap<>();

    /** One notice per removal batch, not one per road: counts + who may see it. */
    private static final Map<ServerLevel,Batch> BATCH=new IdentityHashMap<>();
    private static final class Batch {
        int rebuilt;
        int failed;
        final Set<UUID> recipients=new HashSet<>();
    }

    static void schedule(ServerLevel level,RoadPlan plan) {
        plan.planning().refreshNeeded(true);RoadManager.get(level).add(plan);
        PENDING.computeIfAbsent(level,key -> new HashSet<>()).add(plan.id());
    }

    /**
     * A road was removed / released. Only roads that really touched it need a new
     * proposal: scheduling every road in the dimension regenerated every blueprint
     * on each removal, and again for the whole dimension on restart.
     */
    public static void removed(ServerLevel level,RoadPlan old) {
        final Set<UUID> adjacent=new HashSet<>();
        for(var junction:RoadJunctions.forRoad(level,old.id()))
            adjacent.add(junction.a().equals(old.id()) ? junction.b() : junction.a());
        // The removed road's footprint is one fixed rect; computing it once keeps the
        // per-road test to a single intersection.
        final var body=old.segments().isEmpty() ? null : RoadCrossingGeometry.body(old);
        // Capture the neighbours before the junction table drops this road.
        for(var road:List.copyOf(RoadManager.get(level).all())) {
            if(road.id().equals(old.id()))continue;
            if(!adjacent.contains(road.id()) && !overlaps(body,road))continue;
            road.planning().remove(old.id());road.planning().externalChanges(true);schedule(level,road);
        }
        RoadJunctions.removeRoad(level,old.id());
    }

    /** A recorded junction, or an actual footprint overlap for unregistered candidates. */
    private static boolean overlaps(final com.YLFXM.rp.crossroads.road.common.RoadGeometry.Rect body,final RoadPlan b) {
        if(body==null || b.segments().isEmpty())return false;
        return body.intersect(RoadCrossingGeometry.body(b))!=null;
    }

    /** A completed shortening can end a relationship while both road plans still exist. */
    public static void reconcileApplied(ServerLevel level,RoadPlan changed) {
        if(changed.appliedGeometry()==null)return;
        boolean changedRelations=false;
        for(var junction:RoadJunctions.forRoad(level,changed.id())) {
            var otherId=junction.a().equals(changed.id()) ? junction.b() : junction.a();
            var other=RoadManager.get(level).get(otherId);
            var next=RoadJunctions.recompute(level,junction);
            if(next==null) {
                RoadJunctions.removePair(level,junction.a(),junction.b());
                changed.planning().remove(otherId);
                if(other!=null) {
                    other.planning().remove(changed.id());other.planning().externalChanges(true);schedule(level,other);
                    RoadLockReconcile.apply(level,other);
                }
                changedRelations=true;
            } else if(!next.equals(junction)) RoadJunctions.put(level,next);
        }
        if(changedRelations)RoadManager.get(level).add(changed);
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        PENDING.clear();BATCH.clear();
        for(ServerLevel level:event.getServer().getAllLevels()) {
            for(var road:RoadManager.get(level).all()) {
                for(var junction:RoadJunctions.forRoad(level,road.id())) {
                    if(RoadManager.get(level).get(junction.a())==null || RoadManager.get(level).get(junction.b())==null)
                        RoadJunctions.removePair(level,junction.a(),junction.b());
                }
            }
            // Isolated roads with no pending refresh need no recompute on every start.
            for(var road:List.copyOf(RoadManager.get(level).all())) {
                if(!road.planning().refreshNeeded() && RoadJunctions.forRoad(level,road.id()).isEmpty())continue;
                boolean before=road.planning().refreshNeeded();
                RoadJunctions.recomputeRoad(level,road.id());
                RoadLockReconcile.apply(level,road);
                RoadManager.get(level).add(road);
                if(before || road.planning().refreshNeeded()) schedule(level,road);
            }
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%20!=0) return;
        for(var entry:List.copyOf(PENDING.entrySet())) {
            var level=entry.getKey();
            var batch=BATCH.computeIfAbsent(level,key -> new Batch());
            for(UUID id:List.copyOf(entry.getValue())) {
                var road=RoadManager.get(level).get(id);
                if(road==null) {entry.getValue().remove(id);continue;}
                if(RoadRules.busy(road,RoadRules.PUBLISH))continue;
                if(road.segments().isEmpty()) {road.planning().refreshNeeded(false);entry.getValue().remove(id);continue;}
                RoadPlan proposal=RoadPlan.load(road.save());
                try {
                    // Relationship removal restores structural defaults while preserving the chosen curve.
                    var blueprint=RoadBlueprintGenerator.generate(proposal,level,Config.defaultLampReserveHeight());
                    if(blueprint==null || !RoadProposal.save(level,proposal,blueprint,RoadProposal.Save.CHECKED)
                            || !RoadProposal.pack(level,road,proposal,blueprint)) throw new IllegalArgumentException("Could not regenerate the road proposal.");
                    proposal.planning().refreshNeeded(false);
                    proposal.planning().relationshipKey(RoadAutoPlanning.relationshipKey(level,proposal));
                    if(proposal.state()!=RoadState.DRAFT)proposal.setState(RoadState.PLANNED);
                    RoadProposal.index(level,proposal,RoadProposal.Audience.NONE,null,null);entry.getValue().remove(id);
                    batch.rebuilt++;collect(level,proposal,batch);
                } catch(RuntimeException ex) {
                    road.planning().refreshNeeded(false);road.planning().issue(RoadNotices.reasonKey(ex));
                    RoadManager.get(level).add(road);entry.getValue().remove(id);
                    batch.failed++;collect(level,road,batch);
                    Crossroads.LOGGER.warn("Crossing proposal regeneration failed for {}",id,ex);
                }
            }
            if(entry.getValue().isEmpty()) {
                PENDING.remove(level);
                flush(level,batch);
            }
            level.players().forEach(Network::sendRoads);
        }
    }

    /** Only players who could manage one of the rebuilt roads see the batch summary. */
    private static void collect(final ServerLevel level,final RoadPlan road,final Batch batch) {
        for(var player:level.players())if(RoadPermissions.canManage(level,player,road))batch.recipients.add(player.getUUID());
    }

    private static void flush(final ServerLevel level,final Batch batch) {
        BATCH.remove(level);
        if(batch.recipients.isEmpty() || (batch.rebuilt==0 && batch.failed==0))return;
        for(var player:level.players()) {
            if(!batch.recipients.contains(player.getUUID()))continue;
            if(batch.rebuilt>0)RoadNotices.chat(player,null,"rebuilt",
                    "rp_crossroads.crossing.rebuilt_count",batch.rebuilt);
            if(batch.failed>0)RoadNotices.chat(player,null,"rebuilt_failed",
                    "rp_crossroads.crossing.rebuilt_failed_count",batch.failed);
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {PENDING.clear();BATCH.clear();}
    private RoadCrossingUpdates() { }
}
