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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.YLFXM.rp.crossroads.CustomRoadPack;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.RoadPermissions;
import com.YLFXM.rp.crossroads.network.ConfirmRoadRepairPayload;
import com.YLFXM.rp.crossroads.network.Network;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
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
@EventBusSubscriber(modid=Crossroads.MODID)
public final class RoadRepair {
    private static final Map<UUID,ServerLevel> ACTIVE=new ConcurrentHashMap<>();
    private static final long PREPARE_TIMEOUT=30*60*1000L;
    private static final long NATIVE_TIMEOUT=30*60*1000L;

    public static boolean begin(final ServerLevel level, final ServerPlayer player, final RoadPlan plan) {
        if (!RoadPermissions.canManage(level,player,plan) || !RoadRules.canRepair(plan)) return false;
        if (!canPrepare(player,plan)) return false;
        if(plan.interruptedConstruction()!=null) {
            final var interrupted=RoadRepairSession.load(plan.interruptedConstruction());
            if(interrupted.resumableCommit()) return resumeCommit(level,player,plan,interrupted);
            try {
                final var retry=RoadRepairSession.retry(interrupted,player.getUUID(),System.currentTimeMillis());
                if(!retry.workBlueprint().isEmpty() && RoadCrossings.verifyFrozen(level,retry))
                    return openPrepared(level,player,plan,retry,com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(retry.workBlueprint(),level.registryAccess()));
                // The shared footprint really changed. Report what moved, release the frozen
                // record and prepare again from the current roads; the player may still build.
                final var changes=RoadCrossings.frozenChanges(level,retry);
                plan.setInterruptedConstruction(null);
                RoadManager.get(level).add(plan);
                Crossroads.LOGGER.info("Released stale frozen crossing work: road={} changes={}",plan.id(),changes);
                tell(player,"rp_crossroads.repair.frozen_mismatch",String.join("; ",changes));
            } catch(IllegalArgumentException ex) {
                plan.setInterruptedConstruction(null);
                RoadManager.get(level).add(plan);
                tell(player,"rp_crossroads.repair.frozen_released",RoadNotices.reason(ex));
            }
        }
        RoadBlueprintStore.preserveBuilt(level,plan);
        return prepareAndOpen(level,player,plan,RoadRepairSession.prepare(plan,player.getUUID(),System.currentTimeMillis()));
    }

    /** Continues a commit interrupted after markCommitting; the journal already holds the redo record. */
    private static boolean resumeCommit(final ServerLevel level,final ServerPlayer player,final RoadPlan plan,final RoadRepairSession session) {
        final Blueprint blueprint=frozenBlueprint(level,plan,session);
        if(blueprint==null) {tell(player,"rp_crossroads.repair.frozen_missing");return false;}
        plan.setRepairSession(session);ACTIVE.put(plan.id(),level);RoadManager.get(level).add(plan);
        try {
            if(RoadJointWork.commit(level,plan,session,blueprint)) {completeCommit(level,plan,session);return true;}
        } catch(RuntimeException ex) {
            Crossroads.LOGGER.error("Could not resume frozen joint commit for {}",plan.id(),ex);
        }
        // Keep the interrupted record, but release the live session so Repair can be retried.
        plan.setRepairSession(null);ACTIVE.remove(plan.id());RoadManager.get(level).add(plan);
        tell(player,"rp_crossroads.repair.commit_incomplete");
        return false;
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
            tell(player,"rp_crossroads.road.session_open"); return false;
        }
        if (ACTIVE.entrySet().stream().anyMatch(entry -> {
            final RoadPlan other=RoadManager.get(entry.getValue()).get(entry.getKey());
            return other != null && other.repairSession()!=null
                    && other.repairSession().phase()==RoadRepairSession.Phase.PREPARED
                    && other.repairSession().owner().equals(player.getUUID());
        })) { tell(player,"rp_crossroads.repair.close_other"); return false; }
        if (RoadNativeBuildTool.findToolSlot(player.getInventory()) < 0) {
            tell(player,"rp_crossroads.plan.no_build_tool"); return false;
        }
        return true;
    }

    private static boolean prepareAndOpen(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                          final RoadRepairSession session) {
        final RoadPlan target=session.target();
        final RoadJointWork.Prepared joint;
        try {
            var authorized=RoadCrossingConsent.prepare(level,player,target);if(authorized==null)return false;
            joint=authorized.work();session.setCrossingGrants(authorized.grants());
        }
        catch(IllegalArgumentException ex) {Crossroads.LOGGER.warn("Road preparation refused: road={} reason={}",plan.id(),ex.getMessage());tellReason(player,ex);return false;}
        RoadCrossings.freeze(level,session,target);
        RoadCrossings.extendFrozen(level,session,joint.updates());
        session.setJointBlueprints(com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(joint.primary()),joint.updates());
        final Blueprint blueprint=joint.work();
        session.setWorkBlueprint(com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(blueprint));
        session.setClearTargets(joint.clearTargets());
        return openPrepared(level,player,plan,session,blueprint);
    }
    private static boolean openPrepared(ServerLevel level,ServerPlayer player,RoadPlan plan,RoadRepairSession session,Blueprint blueprint) {
        try {RoadCrossingConsent.validate(level,player.getUUID(),session);}
        catch(IllegalArgumentException ex) {tellReason(player,ex);return false;}
        if(!RoadBlueprintStore.saveChecked(level,plan,blueprint)) {
            tell(player,"rp_crossroads.repair.save_failed");return false;
        }
        final long version=RoadTemporaryPack.replace(level,plan,session.target(),blueprint);
        if (version<0) { tell(player,"rp_crossroads.repair.publish_failed"); return false; }
        RoadBuildWatcher.stop(plan);
        plan.setRepairSession(session); plan.setState(RoadState.PLANNED);
        RoadManager.get(level).add(plan); ACTIVE.put(plan.id(),level);
        sync(level);
        RoadNativeBuildTool.open(player,plan,blueprint,version,session.token());
        Crossroads.LOGGER.info("Road construction prepared: road={} session={} owner={} previous={}",plan.id(),session.token(),player.getUUID(),session.previousState());
        return true;
    }

    public static void confirm(final ServerPlayer player, final ConfirmRoadRepairPayload payload) {
        final ServerLevel level=player.serverLevel();
        final RoadPlan plan=RoadManager.get(level).get(payload.planId());
        if (plan==null || plan.repairSession()==null) {
            RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.construction.expired"));
            return;
        }
        final RoadRepairSession session=plan.repairSession();
        if (!session.ownedBy(player.getUUID(),payload.token()) || session.phase()!=RoadRepairSession.Phase.PREPARED) {
            RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.construction.expired"));
            return;
        }
        // Two different facts, so two different notices: permission uses the vanilla
        // denial the rest of the mod already sends, and a missing tool reuses the
        // existing inventory notice instead of one sentence covering both.
        if (!RoadPermissions.canManage(level,player,plan)) {
            cancel(level,plan,"permission missing"); RoadPermissions.notifyDenied(player); return;
        }
        if (RoadNativeBuildTool.findToolSlot(player.getInventory())<0) {
            cancel(level,plan,"tool missing"); tell(player,"rp_crossroads.plan.no_build_tool"); return;
        }
        final var mismatch=RoadRepairSelection.validate(CustomRoadPack.PACK_NAME,RoadTemporaryPack.pathFor(plan),
                plan.anchorPos(),RotationMirror.NONE.ordinal(),payload.pack(),payload.path(),payload.pos(),payload.rotation());
        if (mismatch!=RoadRepairSelection.Mismatch.NONE) {
            Crossroads.LOGGER.warn("Repair confirmation rejected: road={} reason={} path={} anchor={} rotation={}",
                    plan.id(),mismatch,payload.path(),payload.pos(),payload.rotation());
            RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.repair.selection_"+mismatch.name().toLowerCase(java.util.Locale.ROOT))); return;
        }
        if(!RoadJointWork.impact(level,session).isEmpty() && !payload.jointApproved()) {
            RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.crossing.approve_required"));return;
        }
        final var modes=com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.values();
        if (payload.mode()<0 || payload.mode()>=modes.length) return;
        final var mode=modes[payload.mode()];
        final boolean survival=mode==com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Survival;
        if (!survival && !player.isCreative()) { tell(player,"rp_crossroads.repair.creative_only"); return; }
        final var handler=survival ? SurvivalBlueprintHandlers.getHandler(payload.handler()) : null;
        if (survival && (!(handler instanceof com.minecolonies.core.placementhandlers.main.SurvivalHandler)
                || IColonyManager.getInstance().getColonyByPosFromWorld(level,plan.controllerPos())==null)) {
            tell(player,"rp_crossroads.repair.survival_needs_colony"); return;
        }
        if(!RoadCrossings.verifyFrozen(level,session)) {cancel(level,plan,"crossing dependency changed");tell(player,"rp_crossroads.repair.crossing_changed");return;}
        try {RoadJointWork.validateFrozen(level,player,session);}
        catch(IllegalArgumentException ex) {tellReason(player,ex);return;}
        final Blueprint blueprint=frozenBlueprint(level,plan,session);
        if (blueprint==null) { cancel(level,plan,"missing frozen blueprint"); return; }
        if(survival) {
            try {
                if(!session.acceptBuilder(RoadBuilderDispatch.dispatch(level,player,plan,blueprint),System.currentTimeMillis()))
                    throw new IllegalStateException("The native builder order was not accepted.");
            } catch(RuntimeException ex) {
                Crossroads.LOGGER.warn("Road builder dispatch failed: road={} session={} reason={}",plan.id(),session.token(),ex.getMessage());
                cancel(level,plan,"builder order rejected before construction");
                tell(player,"rp_crossroads.repair.order_rejected",RoadNotices.reason(ex));return;
            }
        } else session.start(RoadRepairSession.Phase.NATIVE,System.currentTimeMillis());
        RoadCrossingConsent.consume(session);
        plan.setState(RoadState.BUILDING); RoadManager.get(level).add(plan); sync(level);
        try {
            if (survival) {
                // Already admitted synchronously above. A rejected request never becomes BUILDING.
                RoadManager.get(level).add(plan);
            } else {
                final var nativeHandler=new CreativeStructureHandler(level,plan.anchorPos(),blueprint,RotationMirror.NONE,mode==com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Pretty);
                Manager.addToQueue(new PlaceStructureOperation(new RoadProtectedStructurePlacer(nativeHandler),player) {
                    @Override public boolean apply(final ServerLevel world) {
                        if (ACTIVE.get(plan.id()) != level) return true;
                        final RoadPlan current=RoadManager.get(level).get(plan.id());
                        if (current==null || current.repairSession()!=session) return true;
                        if (!world.dimension().equals(level.dimension())) return false;
                        if (!RoadPermissions.canManage(level,player,current)) { cancel(level,current,"permission revoked"); return true; }
                        try {
                            if(!RoadCrossings.verifyFrozen(level,session)) {cancel(level,current,"crossing dependency changed during placement");return true;}
                            RoadCrossingConsent.validate(level,session.owner(),session);
                            final boolean done=super.apply(world);
                            if (done) { session.finishNative(); poll(level,current,false); }
                            return done;
                        } catch (final RuntimeException ex) {
                            Crossroads.LOGGER.error("Native repair placement failed for {}",plan.id(),ex);
                            cancel(level,current,"native placement failed"); return true;
                        }
                    }
                });
            }
            Crossroads.LOGGER.info("Repair confirmed: road={} session={} mode={}",plan.id(),session.token(),session.phase());
        } catch (final RuntimeException ex) {
            Crossroads.LOGGER.error("Repair start failed for {}",plan.id(),ex);
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
        if(session.committing()) {
            // The journal was created only after complete world validation. Redo writes, not world placement.
            if(RoadJointWork.commit(level,plan,session,frozenBlueprint(level,plan,session)))completeCommit(level,plan,session);
            return;
        }
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
            try {RoadCrossingConsent.validate(level,session.owner(),session);}
            catch(IllegalArgumentException ex) {RoadDataCleaner.releaseWorkOrders(plan);cancel(level,plan,ex.getMessage());return;}
            if(!RoadCrossings.verifyFrozen(level,session)) {
                RoadDataCleaner.releaseWorkOrders(plan);cancel(level,plan,"crossing dependency changed during builder work");return;
            }
            var owner=level.getServer().getPlayerList().getPlayer(session.owner());
            if(owner!=null) {
                try {RoadJointWork.validateFrozen(level,owner,session);}
                catch(IllegalArgumentException ex) {RoadDataCleaner.releaseWorkOrders(plan);cancel(level,plan,"related road permission changed");return;}
            }
            if (RoadBuildWatcher.hasWorkOrder(plan)) {
                RoadProtectedIterator.protectOrders(plan);
                if (!session.orderObserved()) { session.observeOrder(); RoadManager.get(level).add(plan); }
                return;
            }
            if (!session.orderObserved() && !restarting && owner!=null && owner.serverLevel()==level && age<PREPARE_TIMEOUT) return;
            if (!session.orderObserved()) { cancel(level,plan,"no builder order accepted"); return; }
        } else if (!session.nativeDone() && !restarting) {
            if (age>NATIVE_TIMEOUT) cancel(level,plan,"native queue timeout");
            return;
        }
        final Blueprint blueprint=frozenBlueprint(level,plan,session);
        if (!RoadBuildWatcher.isBuilt(level,session.target(),blueprint)) {
            cancel(level,plan,"construction incomplete or cancelled"); return;
        }
        if(session.clearTargets().stream().anyMatch(pos -> !level.getBlockState(pos).isAir())) {
            cancel(level,plan,"old elevated road or lamp targets were not cleared"); return;
        }
        if (blueprint==null || !RoadJointWork.commit(level,plan,session,blueprint)) {
            Crossroads.LOGGER.warn("Joint snapshot commit will be retried for {}",plan.id());return;
        }
        completeCommit(level,plan,session);
    }

    private static void completeCommit(ServerLevel level,RoadPlan plan,RoadRepairSession session) {
        plan.setInterruptedConstruction(null);session.commitRows(plan);plan.setAllowBlueprintEdit(false);plan.setRepairSession(null);
        RoadBuildWatcher.markBuiltAfterSnapshot(level,plan);
        RoadCrossingUpdates.reconcileApplied(level,plan);
        try {
            RoadJointJournal.finish(level,session);ACTIVE.remove(plan.id());
            Crossroads.LOGGER.info("Repair completed: road={} session={}; joint snapshots checkpointed",plan.id(),session.token());
        } catch(java.io.IOException ex) {
            plan.setRepairSession(session);RoadManager.get(level).add(plan);
            Crossroads.LOGGER.error("Joint snapshot checkpoint will be retried for {}",plan.id(),ex);
        }
    }
    private static Blueprint frozenBlueprint(ServerLevel level,RoadPlan plan,RoadRepairSession session) {
        return session.workBlueprint().isEmpty() ? RoadBlueprintStore.load(level,plan)
                : com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(session.workBlueprint(),level.registryAccess());
    }

    private static void cancel(final ServerLevel level, final RoadPlan plan, final String reason) {
        final RoadRepairSession session=plan.repairSession();
        if (session==null) return;
        final boolean unconfirmed=session.phase()==RoadRepairSession.Phase.PREPARED;
        // Explicitly authorized: closing/reopening the unconfirmed tool in the same planning
        // cycle keeps its bounded approval. The old tool token is still removed below.
        // Permission loss, disconnect, timeout and other cancellation causes consume it.
        if(unconfirmed && !"tool closed before confirm".equals(reason))RoadCrossingConsent.consume(session);
        if(!unconfirmed)plan.setInterruptedConstruction(session.save());
        plan.setRepairSession(null); ACTIVE.remove(plan.id());
        plan.setState(unconfirmed ? session.previousState() : RoadState.PLANNED);
        RoadManager.get(level).add(plan);
        if (plan.state()==RoadState.BUILT) RoadTemporaryPack.delete(level,plan);
        sync(level);
        final ServerPlayer owner=level.getServer().getPlayerList().getPlayer(session.owner());
        if (owner!=null) tell(owner,unconfirmed ? "rp_crossroads.repair.cancelled" : "rp_crossroads.repair.interrupted");
        if(!unconfirmed && !session.neighborUpdates().isEmpty())for(var affected:level.players())
            if(affected!=owner && RoadPermissions.canManage(level,affected,plan))tell(affected,"rp_crossroads.repair.crossing_interrupted",RoadNames.label(plan));
        Crossroads.LOGGER.info("Repair cancelled: road={} session={} reason={}",plan.id(),session.token(),reason);
    }
    private static void sync(final ServerLevel level) { level.players().forEach(Network::sendRoads); }
    private static void tell(final ServerPlayer player, final String langKey, final Object... args) { RoadNotices.chat(player,null,langKey,langKey,args); }
    /** A failed check: the reason is mapped to a lang key, never shown as English text. */
    private static void tellReason(final ServerPlayer player, final Throwable failure) {
        RoadNotices.send(player,null,"t:"+RoadNotices.reasonKey(failure),RoadNotices.reason(failure));
    }

    @SubscribeEvent public static void started(final ServerStartedEvent event) {
        ACTIVE.clear();
        for(final ServerLevel level:event.getServer().getAllLevels()) {
            RoadJointJournal.recover(level);
            for(final RoadPlan plan:java.util.List.copyOf(RoadManager.get(level).all()))
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
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void beforeTick(final ServerTickEvent.Pre event) {
        // Native decoration orders are created by ServerFutureProcessor at LevelTick.Post.
        // Protect them before the next entity/worker tick, rather than waiting for the 20-tick completion poll.
        for(var entry:java.util.List.copyOf(ACTIVE.entrySet())) {
            RoadPlan plan=RoadManager.get(entry.getValue()).get(entry.getKey());
            if(plan!=null && plan.repairSession()!=null && plan.repairSession().phase()==RoadRepairSession.Phase.SURVIVAL)
                RoadProtectedIterator.protectOrders(plan);
        }
        for(var level:event.getServer().getAllLevels())for(var plan:RoadManager.get(level).all()) {
            if(plan.interruptedConstruction()!=null && plan.state()!=RoadState.REMOVING &&
                    (plan.repairSession()==null || plan.repairSession().phase()==RoadRepairSession.Phase.PREPARED)) {
                // A late native second confirmation must not revive cancelled work without its token/locks.
                if(RoadDataCleaner.releaseWorkOrders(plan)>0)Crossroads.LOGGER.warn("Rejected a late builder order for interrupted road {}",plan.id());
            }
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
