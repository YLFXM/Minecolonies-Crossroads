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
package com.YLFXM.rp.crossroads.road.common;

import com.YLFXM.rp.crossroads.Crossroads;
import com.YLFXM.rp.crossroads.network.Network;
import com.YLFXM.rp.crossroads.road.RoadBlueprintStore;
import com.YLFXM.rp.crossroads.road.RoadManager;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import com.YLFXM.rp.crossroads.road.RoadTemporaryPack;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Single source of truth for publishing a road proposal: canonical snapshot,
 * structure-pack copy, road index and client sync.
 *
 * <p>The nine proposal-publishing entry points intentionally differ in their
 * order (see {@code docs/道路系统/整合优化/R7-发布流水线与交接收尾统一.md} §2):
 * {@code saveChecked} short-circuits before the pack write, {@code setState}
 * has three different positions, the platform action publishes inside a
 * {@code RoadCrossings.scope}, and the crossing tick coalesces its broadcast
 * outside the loop. This class therefore does NOT own the whole pipeline —
 * it owns the invariants only, and callers keep their own order and scopes.</p>
 */
public final class RoadProposal {
    /** CHECKED uses saveChecked and reports the write result; VOID keeps the legacy unchecked write. */
    public enum Save { CHECKED, VOID }
    /** NONE writes the index only; PLAYER refreshes one operator; ALL broadcasts. */
    public enum Audience { NONE, PLAYER, ALL }

    /** Writes the canonical snapshot. */
    public static boolean save(final ServerLevel level, final RoadPlan plan, final Blueprint image, final Save mode) {
        if (mode == Save.CHECKED) return RoadBlueprintStore.saveChecked(level, plan, image);
        RoadBlueprintStore.save(level, plan, image);
        return true;
    }

    /** Writes the structure-pack copy; a {@code -1} pack version is now a hard gate instead of being ignored. */
    public static boolean pack(final ServerLevel level, final RoadPlan previous, final RoadPlan plan,
                               final Blueprint image) {
        if (RoadTemporaryPack.replace(level, previous, plan, image) >= 0) return true;
        Crossroads.LOGGER.warn("Could not publish the structure-pack copy for road {}; keeping the previous state", plan.id());
        return false;
    }

    /** Sends the road index to the requested audience. */
    public static void sync(final ServerLevel level, final Audience audience,
                            final ServerPlayer player, final IColony colony) {
        switch (audience) {
            case ALL -> level.players().forEach(Network::sendRoads);
            case PLAYER -> Network.sendRoads(player, colony);
            case NONE -> { }
        }
    }

    /** Replaces the plan in the road index, then syncs. */
    public static void index(final ServerLevel level, final RoadPlan plan, final Audience audience,
                             final ServerPlayer player, final IColony colony) {
        RoadManager.get(level).add(plan);
        sync(level, audience, player, colony);
    }

    /** Standard combination for entries ordered snapshot -> pack -> index -> sync. */
    public static boolean publish(final ServerLevel level, final RoadPlan previous, final RoadPlan plan,
                                  final Blueprint image, final boolean includePack, final Audience audience,
                                  final ServerPlayer player, final IColony colony) {
        if (!save(level, plan, image, Save.CHECKED)) return false;
        if (includePack && !pack(level, previous, plan, image)) return false;
        index(level, plan, audience, player, colony);
        return true;
    }

    private RoadProposal() { }
}
