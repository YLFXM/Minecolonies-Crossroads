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

import com.YLFXM.rp.crossroads.*;
import com.YLFXM.rp.crossroads.road.common.RoadAxisMath;
import com.YLFXM.rp.crossroads.road.common.RoadProposal;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Raises a layered crossing that no longer meets the clearance rule.
 *
 * <p>A repair only validates: it never re-solves the height. That is fine while the
 * rule does not change, but the moment {@link RoadCrossings#LAYERED_SEPARATION} moved
 * from three to five (three blocks of walking room plus the deck's foundation), every
 * crossing built under the old rule became permanently unrepairable — the pre-flight
 * refused, and there was no entry point left that could raise the deck. The player
 * could not even demolish their way out, because the demolition pre-flight runs the
 * same check.</p>
 *
 * <p>So the repair path heals first: solve the approach again with the relation kept
 * and the player's ad-hoc platform height dropped, which is exactly what makes the
 * bridge rise. Nothing is written unless a candidate really reaches the clearance.</p>
 */
public final class RoadClearanceHeal {
    /** Lowest deck separation between this road and one layered neighbour, or {@code Integer.MAX_VALUE}. */
    public static int separation(final RoadPlan plan, final RoadPlan peer) {
        if (plan.segments().isEmpty() || peer.segments().isEmpty() || plan.axis() == peer.axis()) {
            return Integer.MAX_VALUE;
        }
        final var rect = RoadCrossingGeometry.body(plan).intersect(RoadCrossingGeometry.body(peer));
        if (rect == null) return Integer.MAX_VALUE;
        final var own = RoadHeightProfile.of(plan);
        final var other = RoadHeightProfile.of(peer);
        int lowest = Integer.MAX_VALUE;
        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                final int ya = own.row(RoadAxisMath.along(plan.axis(), x, z)).baseY();
                final int yb = other.row(RoadAxisMath.along(peer.axis(), x, z)).baseY();
                lowest = Math.min(lowest, Math.abs(ya - yb));
            }
        }
        return lowest;
    }

    /** True when every layered neighbour is too close and nothing else is wrong with the pair. */
    static boolean needsHealing(final ServerLevel level, final RoadPlan plan, final List<RoadPlan> peers) {
        for (final RoadPlan peer : peers) {
            if (!RoadCrossings.setting(level, plan, peer).layered()) continue;
            if (separation(plan, peer) < RoadCrossings.LAYERED_SEPARATION) return true;
        }
        return false;
    }

    /**
     * @return true when the plan was replaced by a healed one and re-published
     */
    public static boolean heal(final ServerLevel level, final ServerPlayer player, final RoadPlan live) {
        if (live == null || live.segments().isEmpty()) return false;
        final List<RoadPlan> peers = new ArrayList<>();
        for (final var neighbour : RoadCrossings.neighbors(level, live)) {
            if (!neighbour.live().id().equals(live.id()) && neighbour.setting().layered()) peers.add(neighbour.live());
        }
        if (peers.isEmpty() || !needsHealing(level, live, peers)) return false;

        // Everything below works on a copy: a heal that cannot reach the clearance must
        // leave the live plan byte-identical, so the ordinary pre-flight still reports
        // the honest reason.
        final RoadPlan candidate = RoadPlan.load(live.save());
        for (final RoadPlan peer : peers) candidate.planning().spanFloor(peer.id(), null);
        try { RoadAutoPlanning.initialize(level, candidate, true); }
        catch (final IllegalArgumentException ignored) { }

        int cleared = 0;
        boolean healed = !needsHealing(level, candidate, peers);
        if (!healed && !candidate.manualRows().isEmpty()) {
            // The player asked for a repair; a hand row is a hint, not a veto. This is the
            // only way out of a locked crossing, so the rows inside the way of the solve go.
            final var rows = new ArrayList<>(candidate.manualRows().keySet());
            rows.forEach(candidate::clearManual);
            try { RoadAutoPlanning.initialize(level, candidate, true); }
            catch (final IllegalArgumentException ignored) { }
            healed = !needsHealing(level, candidate, peers);
            if (healed) cleared = rows.size();
        }
        if (!healed) {
            Crossroads.LOGGER.info("Clearance heal left road {} alone: no reachable height meets {} blocks of deck separation",
                    live.id(), RoadCrossings.LAYERED_SEPARATION);
            return false;
        }

        live.adoptRows(candidate.rows());
        live.adoptPlanning(candidate.planning());
        if (live.state() != RoadState.DRAFT) live.setState(RoadState.PLANNED);
        final var image = RoadBlueprintGenerator.generate(live, level, Config.defaultLampReserveHeight(), player);
        if (image == null
                || !RoadProposal.publish(level, live, live, image, true, RoadProposal.Audience.ALL, player, null)) {
            Crossroads.LOGGER.warn("Clearance heal solved road {} but could not publish it", live.id());
            return false;
        }
        int lowest = Integer.MAX_VALUE;
        for (final RoadPlan peer : peers) lowest = Math.min(lowest, separation(live, peer));
        Crossroads.LOGGER.info("Clearance heal raised road {}: separation={} clearedHandRows={}", live.id(), lowest, cleared);
        if (cleared > 0) {
            RoadNotices.chat(player, live.id(), "clearance_heal_rows",
                    "rp_crossroads.clearance.raised_rows", cleared, lowest);
        } else {
            RoadNotices.chat(player, live.id(), "clearance_heal",
                    "rp_crossroads.clearance.raised", lowest);
        }
        return true;
    }

    private RoadClearanceHeal() { }
}
