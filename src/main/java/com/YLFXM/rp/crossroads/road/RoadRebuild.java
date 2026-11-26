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
import com.YLFXM.rp.crossroads.road.common.RoadGeometry;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Two-phase rebuild for a built road whose footprint changes.
 *
 * <p>A width or mirror change moves pavement to cells the old road never owned,
 * and it takes pavement away from cells it did. In place that is a half-written
 * road: the stale side survives because a build order never removes blocks
 * outside its own blueprint, and a junction cell is written by two roads at
 * once, so the neighbour would be rewritten underneath itself. The road is
 * therefore demolished first and built again in the same shape change.</p>
 *
 * <p>The shape is planned <em>before</em> anything is touched: planning with the
 * junction records still in place is what proves the new footprint can keep each
 * crossing at the same level (or on the same side of a layered crossing). The
 * candidate also unlocks the old plan's locked rows first — those heights were
 * pinned by the previous platform and would otherwise force the new road into a
 * shape derived from the road that is about to disappear.</p>
 *
 * <p>Order inside the demolition is fixed and matters: the removal blueprint
 * (which carries the shared-cell protection) is written and published, and only
 * then are the junction records dropped. Reversed, the protection would be
 * computed with no junction left to recognise, and the neighbour's pavement in
 * the shared cells would be demolished with this road.</p>
 */
public final class RoadRebuild {
    /** What the player asked for. Everything else stays with the plan. */
    public record Shape(String style, String series, int width, WalkRange walk, int tier, boolean mirror) { }

    /** The shape a plan currently carries, at {@code tier}. */
    public static Shape shapeOf(final RoadPlan plan, final int tier) {
        return new Shape(plan.unitStyle(), plan.series(), plan.width(), plan.walkRange(), tier, plan.mirror());
    }

    /**
     * Whether applying {@code target} has to demolish the road first.
     *
     * <p>Compared against what the world actually holds, not against the plan's
     * own fields: a mirror flipped or a width changed but not yet built leaves
     * the plan already describing the new shape while the world still holds the
     * old one. Only the footprint decides.</p>
     */
    public static boolean needed(final ServerLevel level, final RoadPlan plan, final Shape target) {
        if (plan == null || plan.segments().isEmpty()) return false;
        final RoadPlan applied = RoadBlueprintStore.appliedPlan(level, plan);
        if (applied == null) return false;
        return footprintDiffers(applied, target);
    }

    /** Pure form of the decision, for the regressions and for reading the intent. */
    static boolean footprintDiffers(final RoadPlan applied, final Shape target) {
        return applied.width() != target.width()
                || !applied.walkRange().equals(target.walk())
                || Integer.signum(applied.widthSide()) != (target.mirror() ? -1 : 1);
    }

    /**
     * The candidate shape, built without touching the live plan.
     *
     * <p>Built through {@link RoadPlanBuilder} rather than by setting fields:
     * mirror is the lateral sign of the whole footprint, and a plan has no
     * setter for it. Identity (id, controller, anchor, names, endpoints) is
     * carried over by {@link RoadReplanning#inherit} so the blueprint file, the
     * wand binding and the controller link all survive.</p>
     */
    static RoadPlan candidate(final ServerLevel level, final RoadPlan live, final Shape target) {
        if (!live.hasEndpoints()) {
            throw new IllegalArgumentException("This road has no stored endpoints; re-select them before changing its width or mirror.");
        }
        final RoadPlan plan = RoadPlanBuilder.build(level, live.anchorPos(), live.controllerPos(), live.controllerFacing(),
                live.endpointA(), live.endpointB(), live.pack(), target.series(),
                target.width(), target.walk().width(), target.mirror() ? -1 : 1, live.lampSpacing()).plan();
        RoadReplanning.inherit(live, plan, live.creator(), false);
        plan.setUnitStyle(target.style());
        plan.setVariant(target.series());
        plan.setWidth(target.width());
        plan.setWalkRange(target.walk());
        plan.setUnitLevel(target.tier());
        plan.setMirror(target.mirror());
        // The old snapshots stay on the candidate while it is planned: the terrain
        // sampler uses them to tell this road's own pavement from the ground under
        // it. They are dropped right after, because none of that pavement is the
        // new shape's target any more.
        for (int row : new ArrayList<>(plan.lockedRows().keySet())) plan.unlock(row);
        return plan;
    }

    /**
     * Plans the candidate and proves every crossing still holds.
     *
     * <p>{@link RoadAutoPlanning} pins the shared rows from the recorded
     * relations, so the solve is what produces a compatible height;
     * {@link RoadCrossings#validate} then checks the result against the same
     * rule the builder would enforce, which is where a genuinely impossible
     * shape (a same-level crossing the new road cannot reach, a layered one
     * that lost its three blocks) is rejected.</p>
     */
    static void check(final ServerLevel level, final RoadPlan candidate, final ServerPlayer player) {
        // Planning and construction must agree on whether this pair may be edited, or
        // the solve reserves the foreign-lamp clearance while the build edits it anyway.
        if (player != null) candidate.planning().planner(player.getUUID());
        RoadAutoPlanning.initialize(level, candidate, true);
        candidate.setAppliedGeometry(null);
        candidate.setAppliedBounds(null);
        for (final RoadCrossings.Neighbor neighbor : RoadCrossings.neighbors(level, candidate)) {
            RoadCrossings.validate(candidate, neighbor);
            if (RoadRules.busy(neighbor.live(), RoadRules.MUTATION)) {
                throw new IllegalArgumentException("A crossing road is busy: " + RoadNames.label(neighbor.live()));
            }
        }
    }

    /**
     * Starts the shape change: check, freeze the demolition, release the junctions.
     *
     * @return true when a demolition order is now queued; the build half follows
     *         automatically from {@link RoadNativeRemoval}
     */
    public static boolean begin(final ServerLevel level, final ServerPlayer player,
                                final RoadPlan live, final Shape target) {
        if (level == null || player == null || live == null) return false;
        if (!RoadPermissions.canManage(level, player, live)) return false;
        // The demolition is the only source of the applied image, and an empty one
        // would send the removal down the management-only path that deletes the plan.
        if (RoadBlueprintStore.appliedPlan(level, live) == null) return false;
        if (!RoadRules.canRemove(live)
                || RoadRules.busy(live, RoadRules.MUTATION)
                || RoadBuildWatcher.hasWorkOrder(live) || RoadBuildWatcher.isWatching(live)) {
            RoadNotices.chat(player, live.id(), "rebuild_busy", "rp_crossroads.style.busy");
            return false;
        }
        final RoadPlan candidate;
        try {
            candidate = candidate(level, live, target);
            check(level, candidate, player);
        } catch (final IllegalArgumentException ex) {
            Crossroads.LOGGER.info("Rebuild refused for {}: {}", live.id(), ex.getMessage());
            RoadNotices.chat(player, live.id(), "rebuild_check", "rp_crossroads.style.rebuild_refused",
                    RoadNotices.reason(ex));
            return false;
        }
        final CompoundTag payload = new CompoundTag();
        payload.putString("style", target.style());
        payload.putString("series", target.series());
        payload.putInt("width", target.width());
        payload.putInt("walkFrom", target.walk().from());
        payload.putInt("walkTo", target.walk().to());
        payload.putInt("level", target.tier());
        payload.putInt("originLevel", live.unitLevel());
        payload.putBoolean("mirror", target.mirror());
        payload.put("target", candidate.save());
        payload.put("relations", relations(level, live));
        Crossroads.LOGGER.info("Rebuild queued: road={} shape {} {} w{} mirror={} level {} -> {}",
                live.id(), target.style(), target.series(), target.width(), target.mirror(),
                live.unitLevel(), target.tier());
        return RoadNativeRemoval.begin(level, player, live, false, payload);
    }

    /**
     * Second half: the site is clear, so the new shape takes over and the build order
     * goes out.
     *
     * <p>The candidate replaces the plan instead of being copied onto it: mirror is
     * the lateral sign of the whole footprint, and a plan has no setter for it — a
     * field-by-field copy would leave the old side pointing the old way while the
     * rows came from the new one. Identity (id, controller, anchor, names,
     * endpoints) was carried over when the candidate was built, so swapping the
     * object keeps the blueprint file, the wand binding and the controller link.</p>
     */
    static void finish(final ServerLevel level, final RoadPlan live, final RoadRemovalSession session) {
        final CompoundTag payload = session.rebuild();
        final RoadPlan next = RoadPlan.load(payload.getCompound("target"));
        next.setAppliedGeometry(null);
        next.setAppliedBounds(null);
        next.setState(RoadState.PLANNED);
        RoadBlueprintStore.deleteApplied(level, live);
        RoadNativeRemoval.forget(level, live);
        RoadManager.get(level).remove(live.id());
        RoadManager.get(level).add(next);
        restore(level, next, payload.getCompound("relations"), false);
        RoadLockReconcile.apply(level, next);
        Crossroads.LOGGER.info("Rebuild: road={} demolished, shape {} {} w{} mirror={} level {}",
                next.id(), payload.getString("style"), payload.getString("series"), payload.getInt("width"),
                payload.getBoolean("mirror"), payload.getInt("level"));
        final ServerPlayer owner = level.getServer().getPlayerList().getPlayer(session.owner());
        if (owner != null && RoadRepair.beginSurvival(level, owner, next, next.unitLevel(),
                payload.getInt("originLevel"))) {
            RoadNotices.chat(owner, next.id(), "rebuild_ordered", "rp_crossroads.style.rebuild_ordered");
            return;
        }
        // Parked: no online owner, or the build half was refused (consent, builder).
        // Publish the new shape as a proposal so the panel has a ghost to repair.
        Crossroads.LOGGER.info("Rebuild parked: road={} owner={} build order not started",
                next.id(), owner == null ? "offline" : "refused");
        RoadCrossingUpdates.schedule(level, next);
    }

    /** The relations this road took part in, as rule intents to put back after the rebuild. */
    static CompoundTag relations(final ServerLevel level, final RoadPlan live) {
        final ListTag pairs = new ListTag();
        for (final RoadJunctions.Junction junction : RoadJunctions.forRoad(level, live.id())) {
            final RoadPlanningState.Crossing rule = RoadJunctions.ruleFor(junction, live.id());
            final UUID other = junction.a().equals(live.id()) ? junction.b() : junction.a();
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("road", other);
            entry.putBoolean("layered", rule.layered());
            entry.putInt("order", rule.order());
            if (rule.platform() != null) entry.putInt("platform", rule.platform());
            pairs.add(entry);
        }
        final CompoundTag out = new CompoundTag();
        out.put("pairs", pairs);
        return out;
    }

    /**
     * Puts the relations back on the new footprint.
     *
     * <p>The rule is what the shape change has to preserve; the rectangle is not,
     * because the new footprint decided it. {@code built} is false after a finished
     * demolition (the road is a proposal again) and true when a cancelled one left
     * the old road standing.</p>
     */
    private static void restore(final ServerLevel level, final RoadPlan live, final CompoundTag relations,
                                final boolean built) {
        final ListTag pairs = relations.getList("pairs", 10);
        for (int i = 0; i < pairs.size(); i++) {
            final CompoundTag entry = pairs.getCompound(i);
            final RoadPlan peer = RoadManager.get(level).get(entry.getUUID("road"));
            if (peer == null || peer.segments().isEmpty()) continue;
            // A record that is still there was never released (the demolition failed
            // before that point); putting it again would only drop its grant.
            if (RoadJunctions.find(level, live.id(), peer.id()) != null) continue;
            final RoadGeometry.Rect rect = RoadCrossingGeometry.body(live).intersect(RoadCrossingGeometry.body(peer));
            if (rect == null) continue;
            final RoadPlanningState.Crossing rule = new RoadPlanningState.Crossing(entry.getBoolean("layered"),
                    entry.getInt("order"), RoadJunctions.nextVersion(level),
                    entry.contains("platform", 3) ? entry.getInt("platform") : null);
            RoadJunctions.put(level, new RoadJunctions.Junction(live.id(), peer.id(), rect, rule, null, built,
                    RoadBlueprintStore.referenceIsApplied(level, peer), 1));
        }
    }

    /**
     * A cancelled demolition leaves the old road standing, so the relations the
     * teardown released have to come back — otherwise the road silently stops
     * being a junction.
     */
    static void abort(final ServerLevel level, final RoadPlan live, final RoadRemovalSession session) {
        final CompoundTag payload = session.rebuild();
        if (payload.isEmpty()) return;
        restore(level, live, payload.getCompound("relations"), true);
        RoadLockReconcile.apply(level, live);
        RoadManager.get(level).add(live);
        Crossroads.LOGGER.info("Rebuild cancelled: road={} crossings restored", live.id());
    }

    private RoadRebuild() { }
}
