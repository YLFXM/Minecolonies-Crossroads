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

import java.util.ArrayList;
import java.util.List;
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
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
        // Same reason as the survival path: a stale clearance would refuse every attempt
        // and the player has no other way to raise a built bridge.
        RoadClearanceHeal.heal(level,player,plan);
        if(plan.interruptedConstruction()!=null) {
            final var interrupted=RoadRepairSession.load(plan.interruptedConstruction());
            if(interrupted.resumableCommit()) return resumeCommit(level,player,plan,interrupted);
            try {
                final var retry=RoadRepairSession.retry(interrupted,player.getUUID(),System.currentTimeMillis());
                if(!retry.workBlueprint().isEmpty() && RoadCrossings.verifyFrozen(level,retry)) {
                    // A resumed session carries the level it was frozen with; its other
                    // levels were never published, so the reopened list offers that one.
                    final Blueprint work=com.ldtteam.structurize.blueprints.v1.BlueprintUtil
                            .readBlueprintFromNBT(retry.workBlueprint(),level.registryAccess());
                    final Blueprint primary=retry.primaryBlueprint().isEmpty() ? work
                            : com.ldtteam.structurize.blueprints.v1.BlueprintUtil
                                    .readBlueprintFromNBT(retry.primaryBlueprint(),level.registryAccess());
                    final RoadJointWork.Prepared prepared=new RoadJointWork.Prepared(
                            work,primary,retry.neighborUpdates(),retry.clearTargets());
                    return openPrepared(level,player,plan,retry,
                            Map.of(Math.max(1,retry.target().unitLevel()),prepared));
                }
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

    /**
     * Freezes the road at {@code targetLevel} and hands it straight to a colony
     * builder, without the native build tool.
     *
     * <p>This is what the panel's Repair and Upgrade buttons use. Both are the
     * same operation from the world's point of view — replace the road with a
     * freshly generated blueprint — so the only difference is the tier the
     * blueprint is generated from. The plan's own tier is untouched until the
     * build commits, so cancelling an upgrade leaves the road at its old tier
     * with nothing to roll back.</p>
     *
     * <p>No build tool is required in the player's inventory: the order goes to
     * the colony, exactly like any other decoration order.</p>
     */
    public static boolean beginSurvival(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                        final int targetLevel) {
        return beginSurvival(level,player,plan,targetLevel,plan.unitLevel());
    }

    /**
     * @param originLevel the tier to fall back to if this order is cancelled. The
     *                    live plan may already carry the new tier (a panel switch
     *                    writes it before asking for the rebuild), so the caller
     *                    has to say what it was.
     */
    public static boolean beginSurvival(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                        final int targetLevel, final int originLevel) {
        if (!RoadPermissions.canManage(level,player,plan) || !RoadRules.canRepair(plan)) return false;
        // The clearance rule is a hard contract and a repair only validates. Without this a
        // crossing built under the old rule can never be repaired again: the pre-flight
        // refuses and there is no other entry point that raises a deck. It runs before the
        // shape decision so a rebuild request on such a road heals too.
        RoadClearanceHeal.heal(level,player,plan);
        // A width or mirror change moves the footprint, and a repair can only write
        // the new one — it never takes the old pavement away. Demolish and build in
        // one shape change instead, but only after the new footprint proved it can
        // keep every crossing it touches.
        final RoadRebuild.Shape shape = RoadRebuild.shapeOf(plan, targetLevel);
        if (RoadRebuild.needed(level, plan, shape)) return RoadRebuild.begin(level, player, plan, shape);
        if (RoadBuildWatcher.hasWorkOrder(plan) || RoadBuildWatcher.isWatching(plan)) {
            tellSessionOpen(player,plan); return false;
        }
        final int maxLevel=RoadUnitLibrary.maxLevel(level.getServer(),plan.unitStyle(),plan.series());
        // A repair asks for the tier the road already has; only an upgrade moves
        // it, and only within what the series actually ships.
        if (targetLevel!=plan.unitLevel() && (targetLevel<1 || targetLevel>maxLevel)) {
            tell(player,"rp_crossroads.plan.upgrade_unavailable"); return false;
        }
        if(plan.interruptedConstruction()!=null) {
            final var interrupted=RoadRepairSession.load(plan.interruptedConstruction());
            // A half-written commit has a redo journal; finishing it needs no tool.
            if(interrupted.resumableCommit()) return resumeCommit(level,player,plan,interrupted);
            // Anything else is a stale plan for work nobody started: drop it and
            // freeze a fresh target instead of resuming a shape that may be wrong.
            plan.setInterruptedConstruction(null);
            RoadManager.get(level).add(plan);
        }
        RoadBlueprintStore.preserveBuilt(level,plan);
        final RoadPlan target=RoadPlan.load(plan.save());
        target.setRepairSession(null);
        target.setUnitLevel(targetLevel);
        return prepareAndDispatch(level,player,plan,
                RoadRepairSession.prepareForTier(target,plan,player.getUUID(),System.currentTimeMillis()));
    }

    private static boolean canPrepare(final ServerPlayer player, final RoadPlan plan) {
        if (RoadBuildWatcher.hasWorkOrder(plan) || RoadBuildWatcher.isWatching(plan)) {
            tellSessionOpen(player,plan); return false;
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

    /**
     * Refuses a second session on a busy road, naming whoever holds it when they are online.
     *
     * <p>The name is the actual remedy: "someone is building" makes the player retry,
     * "YLFXM is building" tells them who to ask. Offline holders fall back to the neutral
     * line, since there is nobody to contact anyway.</p>
     */
    private static void tellSessionOpen(final ServerPlayer player, final RoadPlan plan) {
        final RoadRepairSession session=plan.repairSession();
        final ServerPlayer owner=session==null ? null
                : player.server.getPlayerList().getPlayer(session.owner());
        if (owner==null) { tell(player,"rp_crossroads.road.session_open"); return; }
        tell(player,"rp_crossroads.road.session_open_by",owner.getGameProfile().getName());
    }

    /**
     * Freezes both this road and every neighbouring road it touches, and returns
     * the blueprint the build will place.
     *
     * <p>Shared by the tool and the order paths: a junction cell has to be
     * resolved the same way whether a player is about to place the blocks or a
     * builder is.</p>
     *
     * @return the work blueprint, or {@code null} when preparation was refused
     */
    private static Map<Integer, RoadJointWork.Prepared> prepareJoint(final ServerLevel level, final ServerPlayer player,
                                                                     final RoadPlan plan, final RoadRepairSession session,
                                                                     final boolean allTiers) {
        final Map<Integer,RoadJointWork.Prepared> tiers=prepareTiers(level,player,plan,session,allTiers);
        if(tiers==null)return null;
        final RoadPlan target=session.target();
        final RoadJointWork.Prepared joint=tiers.get(baseTier(session,tiers));
        RoadCrossings.freeze(level,session,target);
        // Generation binds the primary on the frozen copy and on the plan the manager
        // holds. The object this flow publishes the tool with can be a third one (the
        // freshly validated geometry of a first build), and it has to carry the same
        // fact or its payload anchor and its blueprint primary disagree by one cell.
        if (plan.blueprintAnchor()==null && target.blueprintAnchor()!=null) {
            plan.setBlueprintAnchor(target.blueprintAnchor());
        }
        RoadCrossings.extendFrozen(level,session,joint.updates());
        session.setJointBlueprints(writeBlueprint(joint.primary()),joint.updates());
        session.setWorkBlueprint(writeBlueprint(joint.work()));
        session.setClearTargets(joint.clearTargets());
        session.setBlueprintBase(RoadNames.blueprintBase(plan));
        session.setBlueprintTiers(tiers.keySet());
        return tiers;
    }

    /**
     * Freezes every level this series ships.
     *
     * <p>Each level is a full proposal, not just its image: the native list shows
     * these very work volumes, so choosing Level 3 has to place the volume that was
     * shown for Level 3. One level is the common case and costs exactly what it did
     * before; the rest are only computed while the tool is open.</p>
     */
    private static Map<Integer, RoadJointWork.Prepared> prepareTiers(final ServerLevel level, final ServerPlayer player,
                                                                     final RoadPlan plan, final RoadRepairSession session,
                                                                     final boolean allTiers) {
        final int max=maxTier(level,session.target());
        if (max<1) {
            tell(player,"rp_crossroads.plan.upgrade_unavailable");
            return null;
        }
        final int baseTier=Math.max(1,Math.min(max,session.target().unitLevel()));
        // Only the tool lists levels. A builder order places one level, so it does not
        // pay for the other proposals.
        final int highest=allTiers ? max : baseTier;
        final Map<Integer,RoadJointWork.Prepared> tiers=new java.util.LinkedHashMap<>();
        CompoundTag grants=null;
        try {
            for (int tier=1; tier<=highest; tier++) {
                final RoadPlan tierTarget=withTier(session.target(),tier);
                final var authorized=RoadCrossingConsent.prepare(level,player,tierTarget);
                if (authorized==null) return null;
                // Grants bind the shared junction cells of one level's work volume, so
                // the set installed on the session has to be the one frozen with it.
                if (tier==baseTier) grants=authorized.grants();
                tiers.put(tier,authorized.work());
            }
        } catch (IllegalArgumentException ex) {
            Crossroads.LOGGER.warn("Road preparation refused: road={} reason={}",plan.id(),ex.getMessage());
            tellReason(player,ex); return null;
        }
        session.setCrossingGrants(grants==null ? new CompoundTag() : grants);
        return tiers;
    }

    /** One level's proposal, for a session whose cached set is gone (server restart). */
    private static RoadJointWork.Prepared prepareTier(final ServerLevel level, final ServerPlayer player,
                                                      final RoadRepairSession session, final int tier) {
        final RoadPlan tierTarget=withTier(session.target(),tier);
        try {
            final var authorized=RoadCrossingConsent.prepare(level,player,tierTarget);
            if (authorized==null) return null;
            // The pair grants bind the shared junction cells of the work volume, and
            // those cells carry the level's blocks: a level chosen at confirmation needs
            // its own receipts, not the ones prepared for another level.
            session.setCrossingGrants(authorized.grants());
            return authorized.work();
        } catch (IllegalArgumentException ex) {
            tellReason(player,ex); return null;
        }
    }

    private static RoadPlan withTier(final RoadPlan base,final int tier) {
        final RoadPlan copy=RoadPlan.load(base.save());copy.setRepairSession(null);copy.setUnitLevel(tier);return copy;
    }

    /**
     * Highest level this series ships, or -1 when it cannot be listed.
     *
     * <p>The native list derives "Level: N" from a single digit in the file name,
     * so a series with ten or more tiers could not be addressed by name.</p>
     */
    static int maxTier(final ServerLevel level,final RoadPlan plan) {
        final int max=RoadUnitLibrary.maxLevel(level.getServer(),plan.unitStyle(),plan.series());
        return max<1 || max>9 ? -1 : max;
    }

    private static int baseTier(final RoadRepairSession session,final Map<Integer,RoadJointWork.Prepared> tiers) {
        final int wanted=session.target().unitLevel();
        return tiers.containsKey(wanted) ? wanted : java.util.Collections.min(tiers.keySet());
    }

    private static Map<Integer,Blueprint> workByTier(final Map<Integer,RoadJointWork.Prepared> tiers) {
        final Map<Integer,Blueprint> out=new java.util.LinkedHashMap<>();
        tiers.forEach((level,prepared) -> out.put(level,prepared.work()));
        return out;
    }

    private static CompoundTag writeBlueprint(final Blueprint blueprint) {
        return com.ldtteam.structurize.blueprints.v1.BlueprintUtil.writeBlueprintToNBT(blueprint);
    }

    private static boolean prepareAndOpen(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                          final RoadRepairSession session) {
        final Map<Integer,RoadJointWork.Prepared> tiers=prepareJoint(level,player,plan,session,true);
        if (tiers==null) return false;
        return openPrepared(level,player,plan,session,tiers);
    }

    /**
     * Publishes an already frozen blueprint and opens the native tool on it.
     *
     * <p>Also the resume path: a session recovered from the journal arrives with
     * its joint blueprints intact, so only publishing and opening are left.</p>
     */
    private static boolean openPrepared(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                        final RoadRepairSession session,
                                        final Map<Integer,RoadJointWork.Prepared> tiers) {
        final long version=publish(level,player,plan,session,tiers);
        if (version<0) { cancel(level,plan,"publishing the prepared road failed"); return false; }
        RoadNativeBuildTool.open(player,plan,tiers.get(baseTier(session,tiers)).work(),version,session.token(),
                workByTier(tiers));
        Crossroads.LOGGER.info("Road construction prepared: road={} session={} owner={} previous={}",plan.id(),session.token(),player.getUUID(),session.previousState());
        return true;
    }

    /**
     * Same preparation, but the order is placed by the colony instead of a player.
     *
     * <p>Used by Repair and Upgrade. The session starts as PREPARED, is admitted
     * through {@link RoadBuilderDispatch}, and only then becomes SURVIVAL — a
     * rejected order never leaves the road in BUILDING.</p>
     */
    private static boolean prepareAndDispatch(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                             final RoadRepairSession session) {
        final Map<Integer,RoadJointWork.Prepared> tiers=prepareJoint(level,player,plan,session,false);
        if (tiers==null) return false;
        final Blueprint blueprint=tiers.get(baseTier(session,tiers)).work();
        if (publish(level,player,plan,session,tiers)<0) {
            cancel(level,plan,"publishing the prepared road failed"); return false;
        }
        try {
            if(!session.acceptBuilder(RoadBuilderDispatch.dispatch(level,player,plan,blueprint),System.currentTimeMillis()))
                throw new IllegalStateException("The native builder order was not accepted.");
        } catch(RuntimeException ex) {
            Crossroads.LOGGER.warn("Road builder dispatch failed: road={} session={} reason={}",plan.id(),session.token(),ex.getMessage());
            cancel(level,plan,"builder order rejected before construction");
            tell(player,"rp_crossroads.repair.order_rejected",RoadNotices.reason(ex));return false;
        }
        RoadCrossingConsent.consume(session);
        plan.setState(RoadState.BUILDING); RoadManager.get(level).add(plan); sync(level);
        Crossroads.LOGGER.info("Road order dispatched: road={} level={} session={}",plan.id(),session.target().unitLevel(),session.token());
        return true;
    }

    /**
     * Writes the frozen blueprint to the pack and parks the plan in PLANNED.
     *
     * @return the published pack version, or -1 when anything refused
     */
    private static long publish(final ServerLevel level, final ServerPlayer player, final RoadPlan plan,
                                final RoadRepairSession session,
                                final Map<Integer,RoadJointWork.Prepared> tiers) {
        // Every native build tool open funnels through here (first build and repair
        // alike). Checking the preview assets now means a deleted or half-written
        // unit file heals before the panel opens instead of on the next restart.
        final CustomRoadPack.UnitCheck units=CustomRoadPack.ensureUnits(level.getServer());
        if (units==CustomRoadPack.UnitCheck.FAILED) {
            Crossroads.LOGGER.error("Refusing to prepare a road build for {}: unit previews are unreadable",
                    player.getName().getString());
            tell(player,"rp_crossroads.plan.units_missing");
            return -1;
        }
        if (units==CustomRoadPack.UnitCheck.REPAIRED) {
            // Re-register before the tool opens so this open already lists the
            // restored files, and bump the revision: clients holding a damaged copy
            // would otherwise compare equal versions and never ask for it again.
            CustomRoadPack.install(level.getServer(),true);
        }
        try {RoadCrossingConsent.validate(level,player.getUUID(),session);}
        catch(IllegalArgumentException ex) {tellReason(player,ex);return -1;}
        final Blueprint chosen=tiers.get(baseTier(session,tiers)).work();
        if(!RoadBlueprintStore.saveChecked(level,plan,chosen)) {
            tell(player,"rp_crossroads.repair.save_failed");return -1;
        }
        final long version=RoadTemporaryPack.publishTiers(level,plan,workByTier(tiers));
        if (version<0) { tell(player,"rp_crossroads.repair.publish_failed"); return -1; }
        RoadBuildWatcher.stop(plan);
        plan.setRepairSession(session); plan.setState(RoadState.PLANNED);
        RoadManager.get(level).add(plan); ACTIVE.put(plan.id(),level);
        sync(level);
        return version;
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
        final String base=session.blueprintBase().isEmpty()?RoadNames.blueprintBase(plan):session.blueprintBase();
        final int maxLevel=maxTier(level,plan);
        if (maxLevel<1) { cancel(level,plan,"no build levels"); tell(player,"rp_crossroads.plan.upgrade_unavailable"); return; }
        final var mismatch=RoadRepairSelection.validateTier(CustomRoadPack.PACK_NAME,RoadTemporaryPack.ROOT_DIR,base,maxLevel,
                com.YLFXM.rp.crossroads.road.common.BlueprintCells.blueprintAnchor(plan),RotationMirror.NONE.ordinal(),
                payload.pack(),payload.path(),payload.pos(),payload.rotation());
        if (mismatch!=RoadRepairSelection.Mismatch.NONE) {
            Crossroads.LOGGER.warn("Repair confirmation rejected: road={} reason={} path={} base={} anchor={} rotation={}",
                    plan.id(),mismatch,payload.path(),base,payload.pos(),payload.rotation());
            RoadNotices.chatMessage(player,null,Component.translatable("rp_crossroads.repair.selection_"+mismatch.name().toLowerCase(java.util.Locale.ROOT))); return;
        }
        // The native tool lists every level, so the chosen file decides the tier. The
        // session already froze that level's work; only a level the player picked after
        // a restart needs it rebuilt.
        final int tier=RoadRepairSelection.tierOf(payload.path(),base);
        if (tier!=plan.unitLevel()) {
            final RoadJointWork.Prepared chosen=prepareTier(level,player,session,tier);
            if (chosen==null) { cancel(level,plan,"chosen level could not be prepared"); return; }
            installTier(level,plan,session,chosen,tier);
            if (!RoadBlueprintStore.saveChecked(level,plan,chosen.work())) {
                cancel(level,plan,"chosen level could not be saved"); tell(player,"rp_crossroads.repair.save_failed"); return;
            }
            RoadTemporaryPack.deleteTierFiles(level,plan,java.util.Set.of(RoadNames.fileName(plan,tier)+".blueprint"));
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
                final var nativeHandler=new CreativeStructureHandler(level,
                        com.YLFXM.rp.crossroads.road.common.BlueprintCells.blueprintAnchor(plan),blueprint,
                        RotationMirror.NONE,mode==com.ldtteam.structurize.network.messages.BuildToolPlacementMessage.HandlerType.Pretty);
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
                            if (done) { session.finishNative(); poll(level,current,false,false); }
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

    /**
     * Runs this plan's completion check on the tick after its builder order was completed.
     *
     * <p>Two differences from the periodic sweep, both deliberate:</p>
     * <ul>
     *   <li>The order is known to have existed, so {@code orderObserved} is recorded first.
     *       Without that, a build fast enough to finish between two sweeps would be
     *       cancelled as "no builder order accepted".</li>
     *   <li>{@code fast} forbids the not-built-yet cancellation; the sweep keeps that job.</li>
     * </ul>
     *
     * @return {@code true} while the session is still live, i.e. the caller may try again.
     */
    public static boolean pollNow(final ServerLevel level, final RoadPlan plan) {
        if (!level.equals(ACTIVE.get(plan.id()))) return false;
        final RoadRepairSession session=plan.repairSession();
        if (session==null) return false;
        if (session.phase()==RoadRepairSession.Phase.SURVIVAL && !session.orderObserved()) {
            session.observeOrder();
            RoadManager.get(level).add(plan);
        }
        poll(level,plan,false,true);
        return level.equals(ACTIVE.get(plan.id()));
    }

    private static void poll(final ServerLevel level, final RoadPlan plan, final boolean restarting,
                             final boolean fast) {
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
            // Fast path (completion signal): the last blocks may still be settling one
            // tick after the order vanished. Never turn that into a cancellation —
            // hand it back to the periodic sweep, which decides as it always has.
            if (fast) {
                if (Crossroads.LOGGER.isDebugEnabled()) {
                    Crossroads.LOGGER.debug("Road {} signalled complete but not fully placed yet; retrying",plan.id());
                }
                return;
            }
            cancel(level,plan,"construction incomplete or cancelled"); return;
        }
        warnUnclearedCells(level,plan,session);
        if (blueprint==null || !RoadJointWork.commit(level,plan,session,blueprint)) {
            Crossroads.LOGGER.warn("Joint snapshot commit will be retried for {}",plan.id());return;
        }
        completeCommit(level,plan,session);
    }

    private static void completeCommit(ServerLevel level,RoadPlan plan,RoadRepairSession session) {
        // The player has finished with this road's build, so the terrain orders
        // they generated in the same session are no longer needed on disk.
        TerrainOrders.clearFor(level.getServer(),session.owner());
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
    /** Swaps the session's frozen work for the chosen level, without touching the world. */
    private static void installTier(final ServerLevel level,final RoadPlan plan,final RoadRepairSession session,
                                    final RoadJointWork.Prepared prepared,final int tier) {
        plan.setUnitLevel(tier);
        RoadManager.get(level).add(plan);
        final RoadPlan target=session.target();
        target.setUnitLevel(tier);
        session.replacePreparedTarget(target);
        session.setJointBlueprints(writeBlueprint(prepared.primary()),prepared.updates());
        session.setWorkBlueprint(writeBlueprint(prepared.work()));
        session.setClearTargets(prepared.clearTargets());
    }

    private static Blueprint frozenBlueprint(ServerLevel level,RoadPlan plan,RoadRepairSession session) {
        return session.workBlueprint().isEmpty() ? RoadBlueprintStore.load(level,plan)
                : com.ldtteam.structurize.blueprints.v1.BlueprintUtil.readBlueprintFromNBT(session.workBlueprint(),level.registryAccess());
    }

    /**
     * Diagnostic for leftover "must become air" cells at completion.
     *
     * <p>Removal is the builder's job, and the native AI never removes blocks it protects:
     * {@link IBuilderUndestroyable} (our own decoration controller is one of them), bedrock,
     * anything still holding fluid. Our decoration controller sits next to the anchor on a cell
     * the blueprint has as air, so demanding that every clear target be air cancelled repairs
     * that had actually finished — the controller can never be cleared, by design.</p>
     *
     * <p>So the check stays as a log line only: leftovers the builder <em>could</em> have removed
     * are reported once, and never block the hand-over. Whatever the builder leaves behind is the
     * native flow's decision, not a reason to drop the road back to PLANNED.</p>
     */
    private static void warnUnclearedCells(final ServerLevel level,final RoadPlan plan,final RoadRepairSession session) {
        final List<BlockPos> left=new ArrayList<>();
        for(final BlockPos pos:session.clearTargets()) {
            final BlockState state=level.getBlockState(pos);
            if(state.isAir()||!state.getFluidState().isEmpty())continue;
            if(state.getBlock() instanceof IBuilderUndestroyable||state.getBlock()==Blocks.BEDROCK)continue;
            left.add(pos);
        }
        if(left.isEmpty())return;
        Crossroads.LOGGER.warn("Road {} finished with {} cell(s) the builder did not clear, first {}: {}",
                plan.id(),left.size(),Math.min(5,left.size()),left.subList(0,Math.min(5,left.size())));
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
        // An upgrade writes the new tier onto the live plan before the order
        // exists, so cancelling has to put the old one back; otherwise the panel
        // would claim a tier the pavement does not have.
        plan.setUnitLevel(session.originLevel());
        plan.setState(unconfirmed ? session.previousState() : RoadState.PLANNED);
        RoadManager.get(level).add(plan);
        // Every level published for this session goes away; a frozen (interrupted)
        // session keeps its files because the resume path reads them.
        if (unconfirmed || plan.state()==RoadState.BUILT) RoadTemporaryPack.deleteTierFiles(level,plan,java.util.Set.of());
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
                if(plan.repairSession()!=null) { ACTIVE.put(plan.id(),level); poll(level,plan,true,false); }
        }
    }
    @SubscribeEvent public static void tick(final ServerTickEvent.Post event) {
        // Terrain blueprints live on disk only until a builder order references
        // them; every five seconds is often enough and costs one pass over the
        // colony order maps.
        if (event.getServer().getTickCount() % 100 == 0) {
            TerrainOrders.sweep(event.getServer());
        }
        if(event.getServer().getTickCount()%20!=0) return;
        for(final var entry:java.util.List.copyOf(ACTIVE.entrySet())) {
            final RoadPlan plan=RoadManager.get(entry.getValue()).get(entry.getKey());
            if(plan==null) ACTIVE.remove(entry.getKey()); else poll(entry.getValue(),plan,false,false);
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
