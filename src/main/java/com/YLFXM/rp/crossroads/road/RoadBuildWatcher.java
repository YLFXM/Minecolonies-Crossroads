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

import com.YLFXM.rp.crossroads.Config;
import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.Network;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.util.BlockInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.YLFXM.rp.crossroads.road.common.RoadBlocks;
import com.YLFXM.rp.crossroads.road.common.BlueprintCells;

/**
 * Watches only recently planned roads (not the whole world). When the native
 * build tool finishes placing the generated blueprint, it marks the plan BUILT
 * and moves the controller inside the road.
 *
 * <p>Completion is verified against <em>every</em> road block of the blueprint,
 * not a sample: Structurize's Complete/Pretty/Paste modes and the builder place
 * blocks over many ticks, and marking a road built mid-placement would move the
 * controller into a cell whose placeholder task is still queued.</p>
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class RoadBuildWatcher {
    private static final Map<UUID, Watch> ACTIVE = new ConcurrentHashMap<>();
    /** Native tool closed; wait a short grace period for queued placement. */
    private static final Map<UUID, Long> CLOSING = new ConcurrentHashMap<>();
    /** Post-completion window in which the controller is re-asserted if something overwrites it. */
    private static final Map<UUID, Guard> GUARD = new ConcurrentHashMap<>();
    /** While the build tool stays open or a work order is running, keep watching. */
    private static final long TIMEOUT_MS = 60L * 60L * 1000L;
    /** After the build tool closes, keep watching this long for a draining queue. */
    private static final long CLOSED_WATCH_MS = 10L * 60L * 1000L;
    /** 20 s of cover for late queued placement tasks after the road is marked built. */
    private static final long GUARD_TICKS = 400L;
    /** How often ownership prefixes are re-checked (ticks): 10 s. */
    private static final int RECONCILE_INTERVAL = 200;
    private record Watch(ServerLevel level, long deadline, Blueprint blueprint) {
    }

    private record Guard(ServerLevel level, long untilTick) {
    }

    public static boolean isWatching(final RoadPlan plan) { return ACTIVE.containsKey(plan.id()); }

    public static void watch(final ServerLevel level, final RoadPlan plan) {
        if (plan.isRepairBusy() || plan.state()==RoadState.REMOVING || plan.state()==RoadState.REMOVED) return;
        // Load the blueprint once: the completion check runs every second and
        // must not re-read and re-parse the NBT from disk each time.
        Blueprint blueprint = RoadBlueprintStore.load(level, plan);
        if (blueprint == null) {
            Crossroads.LOGGER.warn("Cannot watch road {}: frozen blueprint missing; will not regenerate an old build with new geometry",plan.id());
            return;
        }
        ACTIVE.put(plan.id(), new Watch(level, System.currentTimeMillis() + TIMEOUT_MS, blueprint));
        CLOSING.remove(plan.id());
    }

    /**
     * Recover BUILDING sessions and plans with real work orders. PLANNED may be an unapplied edit,
     * so matching world blocks are not evidence that construction was requested.
     * Native instant-placement queues are in-memory and do not survive restart.
     * A BUILDING session whose order disappeared still needs completion/cancellation reconciliation.
     */
    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        final MinecraftServer server = event.getServer();
        int restored = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            for (final RoadPlan plan : RoadManager.get(level).all()) {
                if (plan.isRepairBusy() || !RoadRules.canPublish(plan)
                        || (plan.state() != RoadState.BUILDING && !hasWorkOrder(plan))) {
                    continue;
                }
                plan.setState(RoadState.BUILDING);
                RoadManager.get(level).add(plan);
                watch(level, plan);
                restored++;
            }
        }
        if (restored > 0) {
            Crossroads.LOGGER.info("Re-watching {} in-progress road(s) after restart", restored);
        }
    }

    public static void requestClose(final ServerLevel level, final RoadPlan plan) {
        CLOSING.put(plan.id(), System.currentTimeMillis() + 3000L);
        // Closing the window stops nothing: Complete/Pretty/Paste may still be
        // draining. Give the plan a fresh window instead of letting the watch
        // created at planning time expire from under it.
        ACTIVE.computeIfPresent(plan.id(), (key, watch) ->
                new Watch(watch.level(), System.currentTimeMillis() + CLOSED_WATCH_MS, watch.blueprint()));
    }

    public static void stop(final RoadPlan plan) {
        ACTIVE.remove(plan.id());
        CLOSING.remove(plan.id());
        GUARD.remove(plan.id());
    }

    public static void markBuilt(final ServerLevel level, final RoadPlan plan) {
        RoadBlueprintStore.saveApplied(level, plan, RoadBlueprintStore.load(level, plan));
        markBuiltAfterSnapshot(level,plan);
    }
    public static void markBuiltAfterSnapshot(final ServerLevel level,final RoadPlan plan) {
        RoadControllerMigration.migrate(level, plan);
        plan.setState(RoadState.BUILT);
        RoadManager.get(level).add(plan);
        level.players().forEach(Network::sendRoads);
        // The road is finished: drop its blueprint from the server pack. The
        // original build tool no longer needs it, and a blueprint sitting in the
        // pack would be broadcast to every player who joins. Repair / Remove use
        // the RoadBlueprintStore snapshot instead.
        RoadTemporaryPack.delete(level, plan);
        ACTIVE.remove(plan.id());
        CLOSING.remove(plan.id());
        GUARD.put(plan.id(), new Guard(level, level.getGameTime() + GUARD_TICKS));
        Crossroads.LOGGER.info("Road {} marked built", plan.id());
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        if (!ACTIVE.isEmpty()) {
            pollActive();
        }
        if (!GUARD.isEmpty()) {
            pollGuard();
        }
        // Ownership is derived from position, so a colony growing over a road
        // changes its owner without touching the plan. Re-check the labels a few
        // times a minute; a full sweep of a dimension's road index is trivial.
        if (event.getServer().getTickCount() % RECONCILE_INTERVAL == 0) {
            reconcileLabels(event.getServer());
        }
    }

    /** Rewrites road labels whose ownership prefix no longer matches. */
    private static void reconcileLabels(final MinecraftServer server) {
        for (final ServerLevel level : server.getAllLevels()) {
            final java.util.List<RoadPlan> changed = new java.util.ArrayList<>();
            for (final RoadPlan plan : RoadManager.get(level).all()) {
                if (RoadNames.reconcile(level, plan)) {
                    changed.add(plan);
                }
            }
            if (changed.isEmpty()) {
                continue;
            }
            // RoadManager#add replaces by id and marks the saved data dirty, but it
            // cannot be called while iterating the index.
            changed.forEach(RoadManager.get(level)::add);
            level.players().forEach(Network::sendRoads);
        }
    }

    /** State transition plus its index write; the caller keeps its own watch/closing cleanup and sync order. */
    private static void setStateAndIndex(final ServerLevel level,final RoadPlan plan,final RoadState state) {
        plan.setState(state);
        RoadManager.get(level).add(plan);
    }

    private static void pollActive() {
        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, Watch> entry : new ArrayList<>(ACTIVE.entrySet())) {
            final UUID id = entry.getKey();
            final Watch watch = entry.getValue();
            final RoadPlan plan = RoadManager.get(watch.level()).get(id);
            if (plan == null || plan.state() == RoadState.REMOVED) {
                ACTIVE.remove(id);
                continue;
            }
            if (plan.isRepairBusy() || plan.state()==RoadState.REMOVING) { ACTIVE.remove(id); continue; }
            if (plan.state() == RoadState.BUILT) {
                ACTIVE.remove(id);
                CLOSING.remove(id);
                continue;
            }
            // Work order first: while a builder order is still in the manager the
            // road is being built, and blocks matching early (the builder may
            // finish the visible layer first) must NOT flip it to BUILT.
            // The order disappearing from the manager is the "building finished"
            // signal — MineColonies has no completion event to subscribe to, but
            // the manager entry vanishing is the same observable fact.
            final boolean workOrder = hasWorkOrder(plan);
            if (workOrder) {
                if (plan.state() != RoadState.BUILDING) {
                    setStateAndIndex(watch.level(),plan,RoadState.BUILDING);
                    watch.level().players().forEach(Network::sendRoads);
                    Crossroads.LOGGER.info("Road {} picked up by a builder work order", id);
                }
                // A builder work order can run for a long time; keep watching
                // instead of timing out and never migrating the controller.
                ACTIVE.put(id, new Watch(watch.level(), now + TIMEOUT_MS, watch.blueprint()));
                continue;
            }

            // No order left. Finish with one block check either way: an order can
            // also be cancelled or replaced, and migrating the controller onto a
            // shape that was never placed would be worse than missing a beat.
            if (isBuilt(watch.level(), plan, watch.blueprint())) {
                if (plan.state() == RoadState.BUILDING) {
                    Crossroads.LOGGER.info("Road {} work order finished; marking built", id);
                }
                markBuilt(watch.level(), plan);
                continue;
            }
            if (plan.state() == RoadState.BUILDING) {
                // The order is gone but the shape is not there: it was cancelled.
                // A confirmed start that never finished goes back to PLANNED with
                // its proposal and the already-placed blocks kept — the design
                // only returns a road to DRAFT when the player opened the tool and
                // never confirmed, which the persistent repair session owns
                // (RoadRepair.cancel restores session.previousState).
                setStateAndIndex(watch.level(),plan,RoadState.PLANNED);
                ACTIVE.remove(id);
                CLOSING.remove(id);
                watch.level().players().forEach(Network::sendRoads);
                Crossroads.LOGGER.info("Road {} lost its work order before completion; back to planned", id);
                continue;
            }

            final Long closeAt = CLOSING.get(id);
            if (closeAt != null && now > closeAt) {
                // The tool closed without finishing: unlock the plan UI, but
                // keep watching. A large Paste can still be draining its queue
                // at this point and must be allowed to complete and migrate.
                //
                // The state only says "no longer locked by an open window": the
                // queue may still be draining and the full check below turns it
                // BUILT when the shape lands. A player who opened the tool and
                // closed it without confirming never reaches here with an
                // unconfirmed session — RoadRepair cancels that case and restores
                // the session's previousState (DRAFT).
                setStateAndIndex(watch.level(),plan,RoadState.PLANNED);
                CLOSING.remove(id);
                watch.level().players().forEach(Network::sendRoads);
                Crossroads.LOGGER.info("Road {} native placement closed; kept planned and still watching", id);
            } else if (closeAt == null && now > watch.deadline()) {
                ACTIVE.remove(id);
                CLOSING.remove(id);
                Crossroads.LOGGER.info("Road {} native placement timed out; blueprint kept", id);
            }
        }
    }

    /**
     * Re-places the controller when a late placement task (Paste/Complete/Pretty)
     * resolves the reserved placeholder over it.
     */
    private static void pollGuard() {
        for (final Map.Entry<UUID, Guard> entry : new ArrayList<>(GUARD.entrySet())) {
            final UUID id = entry.getKey();
            final Guard guard = entry.getValue();
            final RoadPlan plan = RoadManager.get(guard.level()).get(id);
            if (plan == null || plan.state() == RoadState.REMOVED
                    || plan.state() != RoadState.BUILT
                    || guard.level().getGameTime() > guard.untilTick()) {
                GUARD.remove(id);
                continue;
            }
            if (!RoadControllerMigration.isController(guard.level().getBlockState(plan.controllerPos()))) {
                Crossroads.LOGGER.warn(
                        "Road {}: controller missing at {} after completion; re-placing",
                        id, plan.controllerPos());
                RoadControllerMigration.migrate(guard.level(), plan);
                guard.level().players().forEach(Network::sendRoads);
            }
        }
    }

    public static boolean hasWorkOrder(final RoadPlan plan) {
        for (final var colony : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies()) {
            if (!RoadWorkOrders.sameDimension(plan, colony)) continue;
            for (final var order : colony.getWorkManager().getWorkOrders().values()) {
                if (RoadWorkOrders.matches(plan, order)) return true;
            }
        }
        return false;
    }

    public static boolean isBuilt(final ServerLevel level, final RoadPlan plan, final Blueprint cached) {
        final Blueprint blueprint = cached;
        if (blueprint == null) {
            return false;
        }
        final BlockPos origin = BlueprintCells.origin(plan, blueprint);
        final List<BlockInfo> sample = new ArrayList<>();
        for (final BlockInfo info : blueprint.getBlockInfoAsList()) {
            final var state = info.getState();
            // Placeholders and air carry no information about build progress.
            if (state.isAir() || !RoadBlocks.isConcrete(state)) {
                continue;
            }
            final BlockPos world = origin.offset(info.getPos());
            if (world.equals(plan.anchorPos()) || world.equals(plan.controllerPos())) {
                continue;
            }
            sample.add(info);
        }
        if (sample.isEmpty()) {
            return false;
        }
        // Full check: the first missing block proves the build is not finished.
        // Compare block types, not full states: the builder may legitimately
        // place the same block with a different waterlogged/axis value.
        for (final BlockInfo info : sample) {
            final BlockPos world = origin.offset(info.getPos());
            final var actual=level.getBlockState(world);
            if (!RoadBlocks.requiredStateMatches(info.getState(),actual)) {
                if (Config.debugLog()) {
                    Crossroads.LOGGER.debug(
                            "Road {} not built yet: {} is {} instead of {}",
                            plan.id(), world, level.getBlockState(world), info.getState());
                }
                return false;
            }
            // Native comparison checks stable DOM textureData without treating
            // dynamic block-entity coordinates as a construction mismatch.
            final var tile=info.getTileEntityData();
            if(tile!=null && tile.contains("textureData") && !com.ldtteam.structurize.util.BlockUtils.areBlockStatesEqual(
                    actual,actual,state -> false,true,(first,second) -> false,tile,level.getBlockEntity(world))) return false;
        }
        return true;
    }

    private RoadBuildWatcher() {
    }
}
