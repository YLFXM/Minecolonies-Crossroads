/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.road;

import com.YLFXM.mcreconstruction.*;
import com.YLFXM.mcreconstruction.network.Network;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.api.ItemStorage;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.client.gui.util.ItemPositionsStorage;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.RemoveFilteredOperation;
import com.ldtteam.structurize.operations.RemoveBlockOperation;
import com.ldtteam.structurize.placement.SimplePlacementContext;
import com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Owns the transaction only; block breaking is performed by MineColonies or Structurize. */
@EventBusSubscriber(modid=MCReconstruction.MODID)
public final class RoadNativeRemoval {
    private static final Map<UUID,Active> ACTIVE=new HashMap<>();
    private static final class Active {
        final ServerLevel level;
        final Blueprint blueprint;
        final long created=System.currentTimeMillis();
        boolean nativeDone;
        Active(ServerLevel level, Blueprint blueprint) { this.level=level; this.blueprint=blueprint; }
    }
    public static boolean begin(ServerLevel level, ServerPlayer player, RoadPlan plan, boolean instant) {
        if (!RoadPermissions.canManage(level,player,plan) || !RoadRules.canRemove(plan)
                || (instant && !player.isCreative())) return false;
        if(plan.state()==RoadState.DRAFT) {
            RoadDataCleaner.clear(level,plan);
            tell(player,"Draft plan, controller and anchor removed; terrain was kept."); return true;
        }
        // A still-running generic native build queue has no cancellation handle.
        if (!plan.isRepairBusy() && RoadBuildWatcher.isWatching(plan)) {
            tell(player,"Close or finish the current build session before demolition."); return false;
        }
        RoadBlueprintStore.preserveBuilt(level,plan);
        Blueprint applied=RoadBlueprintStore.loadApplied(level,plan);
        if (applied==null) {
            RoadDataCleaner.clear(level,plan);
            tell(player,"Road management removed; no proven applied snapshot, terrain was kept."); return true;
        }
        if (!loaded(level,plan,applied)) { tell(player,"Load the entire road before starting demolition."); return false; }
        IColony colony=null;
        BuildingBuilder builder=null;
        if (!instant) {
            colony=IColonyManager.getInstance().getColonyByPosFromWorld(level,plan.controllerPos());
            boolean wilderness=colony==null;
            if (wilderness) colony=IColonyManager.getInstance().getAllColonies().stream()
                    .filter(c -> c.getDimension().equals(level.dimension()))
                    .min(Comparator.comparingDouble(c -> c.getCenter().distSqr(plan.anchorPos()))).orElse(null);
            if (colony==null) { tell(player,"No colony in this dimension can accept demolition."); return false; }
            if (!RoadPermissions.canManage(player,colony)) {
                RoadPermissions.notifyDenied(player); return false;
            }
            final IColony destination=colony;
            final boolean outside=wilderness;
            builder=destination.getServerBuildingManager().getBuildings().values().stream()
                    .filter(b -> b instanceof BuildingBuilder && b.getBuildingLevel()>0 && !b.getAllAssignedCitizen().isEmpty())
                    .map(b -> (BuildingBuilder)b)
                    .filter(b -> !outside || withinRange(b.getPosition(),level,plan,applied,Config.wildernessWorkerMaxDistance()))
                    .min(Comparator.comparingDouble(b -> b.getPosition().distSqr(plan.anchorPos()))).orElse(null);
            if (builder==null) {
                tell(player,wilderness ? "No staffed builder within the wilderness demolition distance limit." : "A staffed level 1 builder is required."); return false;
            }
        }
        final RoadRemovalSession session=new RoadRemovalSession(player.getUUID(),instant,colony==null ? 0 : colony.getID());
        if (!RoadBlueprintStore.saveRemoval(level,plan,session,applied)) {
            tell(player,"Could not save the demolition blueprint."); return false;
        }
        List<ItemPositionsStorage> groups=instant ? creativeTargets(level,plan,applied) : List.of();
        if (groups==null) { RoadBlueprintStore.deleteRemoval(level,plan,session); tell(player,"Native scan deletion cannot match one of the road blocks."); return false; }
        RoadRepair.forget(plan);
        RoadDataCleaner.releaseWorkOrders(plan);
        RoadBuildWatcher.stop(plan);
        plan.setRemovalSession(session); plan.setState(RoadState.REMOVING);
        RoadManager.get(level).add(plan);
        final Active active=new Active(level,applied); ACTIVE.put(plan.id(),active);
        try {
            if (instant) {
                active.nativeDone=groups.isEmpty();
                final ServerPlayer actor=player;
                final com.ldtteam.structurize.util.ITickedWorldOperation nativeOperation=groups.size()==1
                        ? new RemoveBlockOperation(actor,groups.getFirst()) : new RemoveFilteredOperation(actor,groups);
                if (!active.nativeDone) Manager.addToQueue(new com.ldtteam.structurize.util.ITickedWorldOperation() {
                    @Override public boolean apply(ServerLevel world) {
                        if (ACTIVE.get(plan.id())!=active || plan.removalSession()!=session) return true;
                        if (world!=level) return false;
                        if (level.getServer().getPlayerList().getPlayer(session.owner())!=actor
                                || actor.serverLevel()!=level || !actor.isCreative() || !RoadPermissions.canManage(level,actor,plan)) {
                            cancel(level,plan,"creative owner disconnected, moved dimension or lost permission"); return true;
                        }
                        if (!loaded(level,plan,applied)) { cancel(level,plan,"creative demolition area unloaded"); return true; }
                        try {
                            boolean done=nativeOperation.apply(world);
                            if(done) active.nativeDone=true;
                            return done;
                        } catch(RuntimeException ex) {
                            MCReconstruction.LOGGER.error("Creative demolition failed {}",plan.id(),ex);
                            cancel(level,plan,"creative queue failed"); return true;
                        }
                    }
                    @Override public com.ldtteam.structurize.util.ChangeStorage getChangeStorage() { return nativeOperation.getChangeStorage(); }
                });
            } else {
                if (!RoadBlueprintStore.publishRemoval(level,plan,session,applied)) throw new IllegalStateException("Could not publish demolition blueprint");
                WorkOrderDecoration order=WorkOrderDecoration.create(WorkOrderType.REMOVE,
                        CustomRoadPack.PACK_NAME,session.path(plan.id()),plan.displayName(),plan.anchorPos(),RotationMirror.NONE,1);
                order.setBlueprint(applied,level); order.setColony(colony); order.setClaimedBy(builder.getPosition());
                // The normal admission path rejects decoration bounds outside colony chunks.
                // This trusted, permission-checked road uses native restoration admission;
                // run the new-order hook afterwards so native tape/notifications are retained.
                colony.getWorkManager().addWorkOrder(order,true);
                if(colony.getWorkManager().getWorkOrder(order.getID())!=order) throw new IllegalStateException("Demolition order rejected");
                order.onAdded(colony,false);
                session.setOrderId(order.getID()); RoadManager.get(level).add(plan);
            }
            sync(level);
            tell(player,instant ? "Native creative demolition queued." : "Builder demolition ordered.");
            MCReconstruction.LOGGER.info("Removal started: road={} token={} instant={} colony={} order={}",plan.id(),session.token(),instant,session.colonyId(),session.orderId());
            return true;
        } catch (RuntimeException ex) {
            MCReconstruction.LOGGER.error("Could not start native demolition {}",plan.id(),ex);
            cancel(level,plan,"native start failed"); return false;
        }
    }
    /** Matches native REMOVE: an ordinary replacement at a target position is still removed. */
    public static boolean removable(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof IBuilderUndestroyable)
                && state.getBlock()!=Blocks.BEDROCK && state.getBlock()!=Blocks.WATER;
    }
    public static boolean target(BlockState blueprintState) { return RoadBlueprintOps.isConcrete(blueprintState); }
    public static boolean withinRange(BlockPos builder,ServerLevel level,RoadPlan plan,Blueprint blueprint,int maximum) {
        final BlockPos origin=RoadBlueprintOps.origin(plan,blueprint);
        return withinRange(builder,blueprint.getBlockInfoAsList().stream().filter(i -> target(i.getState()))
                .map(i -> origin.offset(i.getPos())).toList(),maximum);
    }
    public static boolean withinRange(BlockPos builder,Iterable<BlockPos> targets,int maximum) {
        if(maximum<0) return false;
        for(var target:targets) if(builder.distSqr(target)>(double)maximum*maximum) return false;
        return true;
    }
    private static boolean loaded(ServerLevel level,RoadPlan plan,Blueprint blueprint) {
        final BlockPos origin=RoadBlueprintOps.origin(plan,blueprint);
        return blueprint.getBlockInfoAsList().stream().filter(i -> target(i.getState()))
                .allMatch(i -> level.hasChunkAt(origin.offset(i.getPos())));
    }
    private static boolean cleared(ServerLevel level,RoadPlan plan,Blueprint blueprint) {
        final BlockPos origin=RoadBlueprintOps.origin(plan,blueprint);
        return blueprint.getBlockInfoAsList().stream().filter(i -> target(i.getState()))
                .noneMatch(i -> removable(level.getBlockState(origin.offset(i.getPos()))));
    }
    private static List<ItemPositionsStorage> creativeTargets(ServerLevel level,RoadPlan plan,Blueprint blueprint) {
        final Map<ItemStorage,ItemPositionsStorage> groups=new LinkedHashMap<>();
        final BlockPos origin=RoadBlueprintOps.origin(plan,blueprint);
        for (var info:blueprint.getBlockInfoAsList()) {
            if (!target(info.getState())) continue;
            BlockPos pos=origin.offset(info.getPos());
            BlockState state=level.getBlockState(pos);
            if (!removable(state)) continue;
            var be=level.getBlockEntity(pos);
            var items=PlacementHandlers.getHandler(level,BlockPos.ZERO,state).getRequiredItems(level,pos,state,
                    be==null ? null : be.saveWithFullMetadata(level.registryAccess()),new SimplePlacementContext(false,RotationMirror.NONE));
            if (items.isEmpty() || items.getFirst().isEmpty()) return null;
            var key=new ItemStorage(items.getFirst());
            groups.computeIfAbsent(key,k -> new ItemPositionsStorage(k)).addPos(pos);
        }
        return new ArrayList<>(groups.values());
    }
    public static boolean matches(RoadPlan plan,com.minecolonies.api.colony.workorders.IWorkOrder order) {
        var session=plan.removalSession();
        return session!=null && !session.instant() && order.getWorkOrderType()==WorkOrderType.REMOVE
                && CustomRoadPack.PACK_NAME.equals(order.getStructurePack()) && session.path(plan.id()).equals(order.getStructurePath());
    }
    private static boolean hasOrder(RoadPlan plan) {
        var session=plan.removalSession();
        return IColonyManager.getInstance().getAllColonies().stream()
                .filter(c -> c.getDimension().equals(plan.dimension()) && c.getID()==session.colonyId())
                .anyMatch(c -> c.getWorkManager().getWorkOrders().values().stream().anyMatch(o -> matches(plan,o)));
    }
    public static void forget(ServerLevel level,RoadPlan plan) {
        ACTIVE.remove(plan.id());
        var session=plan.removalSession();
        if (session!=null) RoadBlueprintStore.deleteRemoval(level,plan,session);
        plan.setRemovalSession(null);
    }
    private static void poll(RoadPlan plan,Active active,boolean restart) {
        var session=plan.removalSession();
        if (session==null) { ACTIVE.remove(plan.id()); return; }
        if (!session.instant() && hasOrder(plan)) return;
        if (session.instant() && !restart && !active.nativeDone) {
            if (System.currentTimeMillis()-active.created>30*60*1000L) cancel(active.level,plan,"native queue timed out");
            return;
        }
        if (!loaded(active.level,plan,active.blueprint)) return;
        if (!cleared(active.level,plan,active.blueprint)) { cancel(active.level,plan,"demolition incomplete or cancelled"); return; }
        MCReconstruction.LOGGER.info("Removal completed: road={} token={}",plan.id(),session.token());
        RoadDataCleaner.clear(active.level,plan);
    }
    private static void cancel(ServerLevel level,RoadPlan plan,String reason) {
        MCReconstruction.LOGGER.info("Removal cancelled: road={} reason={}",plan.id(),reason);
        // Cancel the native order before deleting its frozen file or invalidating its identity.
        final var session=plan.removalSession();
        RoadDataCleaner.releaseWorkOrders(plan);
        if(session!=null) {
            final var owner=level.getServer().getPlayerList().getPlayer(session.owner());
            if(owner!=null) tell(owner,"Demolition cancelled; the road plan and unapplied edits were kept.");
        }
        forget(level,plan); plan.setState(RoadState.PLANNED); RoadManager.get(level).add(plan); sync(level);
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        ACTIVE.clear();
        for (ServerLevel level:event.getServer().getAllLevels()) for (RoadPlan plan:List.copyOf(RoadManager.get(level).all())) {
            var session=plan.removalSession(); if(session==null) continue;
            Blueprint blueprint=RoadBlueprintStore.loadRemoval(level,plan,session);
            if(blueprint==null) { cancel(level,plan,"missing demolition snapshot"); continue; }
            if(!session.instant() && !RoadBlueprintStore.publishRemoval(level,plan,session,blueprint)) { cancel(level,plan,"cannot restore demolition publication"); continue; }
            Active active=new Active(level,blueprint); active.nativeDone=session.instant();
            ACTIVE.put(plan.id(),active); poll(plan,active,true);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%20!=0) return;
        for(var entry:List.copyOf(ACTIVE.entrySet())) {
            var active=entry.getValue(); var plan=RoadManager.get(active.level).get(entry.getKey());
            if(plan==null) ACTIVE.remove(entry.getKey()); else poll(plan,active,false);
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ACTIVE.clear(); }
    private static void sync(ServerLevel level) { level.players().forEach(Network::sendRoads); }
    private static void tell(ServerPlayer player,String message) { player.displayClientMessage(Component.literal(message),false); }
    private RoadNativeRemoval() { }
}
