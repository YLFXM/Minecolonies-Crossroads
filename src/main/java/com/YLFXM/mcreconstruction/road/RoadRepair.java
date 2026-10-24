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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.YLFXM.mcreconstruction.Config;
import com.YLFXM.mcreconstruction.CustomRoadPack;
import com.YLFXM.mcreconstruction.MCReconstruction;
import com.YLFXM.mcreconstruction.RoadPermissions;
import com.YLFXM.mcreconstruction.network.ConfirmRoadRepairPayload;
import com.YLFXM.mcreconstruction.network.Network;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.SurvivalBlueprintHandlers;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-authoritative native repair transaction. No custom block-placement loop. */
@EventBusSubscriber(modid=MCReconstruction.MODID)
public final class RoadRepair {
    private static final Map<UUID,ServerLevel> ACTIVE=new ConcurrentHashMap<>();
    private static final long PREPARE_TIMEOUT=30*60*1000L;
    private static final long NATIVE_TIMEOUT=30*60*1000L;

    public static boolean begin(final ServerLevel level, final ServerPlayer player, final RoadPlan plan) {
        if (!RoadPermissions.canManage(level,player,plan) || !RoadRules.canRepair(plan)) return false;
        if (!canPrepare(player,plan)) return false;
        RoadBlueprintStore.preserveBuilt(level,plan);
        return prepareAndOpen(level,player,plan,RoadRepairSession.prepare(plan,player.getUUID(),System.currentTimeMillis()));
    }

    /** plan is the newly validated geometry; the stored road must still be DRAFT. */
    public static boolean beginInitial(final ServerLevel level, final ServerPlayer player, final RoadPlan plan) {
        final RoadPlan original=RoadManager.get(level).get(plan.id());
        if (original==null || !RoadRules.canCreate(original) || !RoadPermissions.canManage(level,player,original)
                || !canPrepare(player,original)) return false;
        return prepareAndOpen(level,player,plan,RoadRepairSession.prepareInitial(plan,player.getUUID(),System.currentTimeMillis()));
    }

    private static boolean canPrepare(final ServerPlayer player, final RoadPlan plan) {
        if (RoadBuildWatcher.hasWorkOrder(plan) || RoadBuildWatcher.isWatching(plan)) {
            tell(player,"This road already has a construction session."); return false;
        }
        if (ACTIVE.entrySet().stream().anyMatch(entry -> {
            final RoadPlan other=RoadManager.get(entry.getValue()).get(entry.getKey());
            return other != null && other.repairSession()!=null
                    && other.repairSession().phase()==RoadRepairSession.Phase.PREPARED
                    && other.repairSession().owner().equals(player.getUUID());
        })) { tell(player,"Close your other repair tool first."); return false; }
        if (RoadNativeBuildTool.findToolSlot(player.getInventory()) < 0) {
            tell(player,"No build tool in your inventory."); return false;
        }
        return true;
    }

    private static boolean prepareAndOpen(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                          final RoadRepairSession session) {
        final RoadPlan target=session.target();
        final Blueprint blueprint=RoadBlueprintGenerator.generate(target,level,Config.defaultLampReserveHeight());
        if (blueprint==null || !RoadBlueprintStore.saveChecked(level,plan,blueprint)) {
            tell(player,"Road blueprint could not be generated or saved."); return false;
        }
        final long version=RoadTemporaryPack.replace(level,plan,target,blueprint);
        if (version<0) { tell(player,"Road blueprint could not be published."); return false; }
        RoadBuildWatcher.stop(plan);
        plan.setRepairSession(session); plan.setState(RoadState.PLANNED);
        RoadManager.get(level).add(plan); ACTIVE.put(plan.id(),level);
        sync(level);
        RoadNativeBuildTool.open(player,plan,blueprint,version,session.token());
        MCReconstruction.LOGGER.info("Road construction prepared: road={} session={} owner={} previous={}",plan.id(),session.token(),player.getUUID(),session.previousState());
        return true;
    }

    public static void confirm(final ServerPlayer player, final ConfirmRoadRepairPayload payload) {
        final ServerLevel level=player.serverLevel();
        final RoadPlan plan=RoadManager.get(level).get(payload.planId());
        if (plan==null || plan.repairSession()==null) {
            player.displayClientMessage(Component.translatable("mcreconstruction.construction.expired"),false);
            return;
        }
        final RoadRepairSession session=plan.repairSession();
        if (!session.ownedBy(player.getUUID(),payload.token()) || session.phase()!=RoadRepairSession.Phase.PREPARED) {
            player.displayClientMessage(Component.translatable("mcreconstruction.construction.expired"),false);
            return;
        }
        if (!RoadPermissions.canManage(level,player,plan) || RoadNativeBuildTool.findToolSlot(player.getInventory())<0) {
            cancel(level,plan,"permission or tool missing"); tell(player,"Repair refused: permission or build tool missing."); return;
        }
        final var mismatch=RoadRepairSelection.validate(CustomRoadPack.PACK_NAME,RoadTemporaryPack.pathFor(plan),
                plan.anchorPos(),RotationMirror.NONE.ordinal(),payload.pack(),payload.path(),payload.pos(),payload.rotation());
        if (mismatch!=RoadRepairSelection.Mismatch.NONE) {
            MCReconstruction.LOGGER.warn("Repair confirmation rejected: road={} reason={} path={} anchor={} rotation={}",
                    plan.id(),mismatch,payload.path(),payload.pos(),payload.rotation());
            player.displayClientMessage(Component.translatable("mcreconstruction.repair.selection_"+mismatch.name().toLowerCase(java.util.Locale.ROOT)),false); return;
        }
        final var modes=com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.values();
        if (payload.mode()<0 || payload.mode()>=modes.length) return;
        final var mode=modes[payload.mode()];
        final boolean survival=mode==com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Survival;
        if (!survival && !player.isCreative()) { tell(player,"Complete and Pretty require creative mode."); return; }
        final var handler=survival ? SurvivalBlueprintHandlers.getHandler(payload.handler()) : null;
        if (survival && (!(handler instanceof com.minecolonies.core.placementhandlers.main.SurvivalHandler)
                || IColonyManager.getInstance().getColonyByPosFromWorld(level,plan.controllerPos())==null)) {
            tell(player,"Survival repair needs the MineColonies builder handler and a colony."); return;
        }
        final Blueprint blueprint=RoadBlueprintStore.load(level,plan);
        if (blueprint==null) { cancel(level,plan,"missing frozen blueprint"); return; }
        session.start(survival ? RoadRepairSession.Phase.SURVIVAL : RoadRepairSession.Phase.NATIVE,System.currentTimeMillis());
        plan.setState(RoadState.BUILDING); RoadManager.get(level).add(plan); sync(level);
        try {
            if (survival) {
                handler.handle(blueprint,CustomRoadPack.PACK_NAME,RoadTemporaryPack.pathFor(plan),false,
                        level,player,plan.anchorPos(),RotationMirror.NONE);
                if (RoadBuildWatcher.hasWorkOrder(plan)) { session.observeOrder(); RoadManager.get(level).add(plan); }
            } else {
                final var nativeHandler=new CreativeStructureHandler(level,plan.anchorPos(),blueprint,RotationMirror.NONE,mode==com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Pretty);
                Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(nativeHandler),player) {
                    @Override public boolean apply(final ServerLevel world) {
                        if (ACTIVE.get(plan.id()) != level) return true;
                        final RoadPlan current=RoadManager.get(level).get(plan.id());
                        if (current==null || current.repairSession()!=session) return true;
                        if (!world.dimension().equals(level.dimension())) return false;
                        if (!RoadPermissions.canManage(level,player,current)) { cancel(level,current,"permission revoked"); return true; }
                        try {
                            final boolean done=super.apply(world);
                            if (done) { session.finishNative(); poll(level,current,false); }
                            return done;
                        } catch (final RuntimeException ex) {
                            MCReconstruction.LOGGER.error("Native repair placement failed for {}",plan.id(),ex);
                            cancel(level,current,"native placement failed"); return true;
                        }
                    }
                });
            }
            MCReconstruction.LOGGER.info("Repair confirmed: road={} session={} mode={}",plan.id(),session.token(),session.phase());
        } catch (final RuntimeException ex) {
            MCReconstruction.LOGGER.error("Repair start failed for {}",plan.id(),ex);
            cancel(level,plan,"placement start failed");
        }
    }

    public static void closed(final ServerPlayer player, final RoadPlan plan, final UUID token) {
        final RoadRepairSession session=plan.repairSession();
        if (session!=null && session.ownedBy(player.getUUID(),token)
                && session.phase()==RoadRepairSession.Phase.PREPARED) cancel(player.serverLevel(),plan,"tool closed before confirm");
    }
    public static void forget(final RoadPlan plan) { ACTIVE.remove(plan.id()); plan.setRepairSession(null); }

    private static void poll(final ServerLevel level, final RoadPlan plan, final boolean restarting) {
        final RoadRepairSession session=plan.repairSession();
        if (session==null) { ACTIVE.remove(plan.id()); return; }
        final long age=System.currentTimeMillis()-session.changedAt();
        if (session.phase()==RoadRepairSession.Phase.PREPARED) {
            final ServerPlayer owner=level.getServer().getPlayerList().getPlayer(session.owner());
            if (!restarting && (owner==null || owner.serverLevel()!=level || !RoadPermissions.canManage(level,owner,plan))) {
                cancel(level,plan,"owner left dimension or lost permission"); return;
            }
            if (restarting || age>PREPARE_TIMEOUT) cancel(level,plan,"unconfirmed session expired");
            return;
        }
        if (session.phase()==RoadRepairSession.Phase.SURVIVAL) {
            if (RoadBuildWatcher.hasWorkOrder(plan)) {
                if (!session.orderObserved()) { session.observeOrder(); RoadManager.get(level).add(plan); }
                return;
            }
            if (!session.orderObserved() && !restarting && age<20000) return;
            if (!session.orderObserved()) { cancel(level,plan,"no builder order accepted"); return; }
        } else if (!session.nativeDone() && !restarting) {
            if (age>NATIVE_TIMEOUT) cancel(level,plan,"native queue timeout");
            return;
        }
        final Blueprint blueprint=RoadBlueprintStore.load(level,plan);
        if (!RoadBuildWatcher.isBuilt(level,session.target(),blueprint)) {
            cancel(level,plan,"construction incomplete or cancelled"); return;
        }
        if (blueprint==null || !RoadBlueprintStore.saveAppliedChecked(level,plan,blueprint)) {
            cancel(level,plan,"could not save completed blueprint"); return;
        }
        session.commitRows(plan); plan.setAllowBlueprintEdit(false); plan.setRepairSession(null); ACTIVE.remove(plan.id());
        RoadBuildWatcher.markBuilt(level,plan);
        MCReconstruction.LOGGER.info("Repair completed: road={} session={}; rows committed",plan.id(),session.token());
    }

    private static void cancel(final ServerLevel level, final RoadPlan plan, final String reason) {
        final RoadRepairSession session=plan.repairSession();
        if (session==null) return;
        final boolean unconfirmed=session.phase()==RoadRepairSession.Phase.PREPARED;
        plan.setRepairSession(null); ACTIVE.remove(plan.id());
        plan.setState(unconfirmed ? session.previousState() : RoadState.PLANNED);
        RoadManager.get(level).add(plan);
        if (plan.state()==RoadState.BUILT) RoadTemporaryPack.delete(level,plan);
        sync(level);
        final ServerPlayer owner=level.getServer().getPlayerList().getPlayer(session.owner());
        if (owner!=null) tell(owner,"Repair cancelled; unapplied edits were kept.");
        MCReconstruction.LOGGER.info("Repair cancelled: road={} session={} reason={}",plan.id(),session.token(),reason);
    }
    private static void sync(final ServerLevel level) { level.players().forEach(Network::sendRoads); }
    private static void tell(final ServerPlayer player, final String message) { player.displayClientMessage(Component.literal(message),false); }

    @SubscribeEvent public static void started(final ServerStartedEvent event) {
        ACTIVE.clear();
        for(final ServerLevel level:event.getServer().getAllLevels()) for(final RoadPlan plan:RoadManager.get(level).all()) {
            if(plan.repairSession()!=null) { ACTIVE.put(plan.id(),level); poll(level,plan,true); }
        }
    }
    @SubscribeEvent public static void tick(final ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%20!=0) return;
        for(final var entry:java.util.List.copyOf(ACTIVE.entrySet())) {
            final RoadPlan plan=RoadManager.get(entry.getValue()).get(entry.getKey());
            if(plan==null) ACTIVE.remove(entry.getKey()); else poll(entry.getValue(),plan,false);
        }
    }
    @SubscribeEvent public static void logout(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for(final var entry:java.util.List.copyOf(ACTIVE.entrySet())) {
            final RoadPlan plan=RoadManager.get(entry.getValue()).get(entry.getKey());
            if(plan!=null && plan.repairSession()!=null && plan.repairSession().owner().equals(player.getUUID())
                    && plan.repairSession().phase()==RoadRepairSession.Phase.PREPARED) cancel(entry.getValue(),plan,"owner disconnected");
        }
    }
    @SubscribeEvent public static void stopped(final ServerStoppedEvent event) { ACTIVE.clear(); }
    private RoadRepair() { }
}
