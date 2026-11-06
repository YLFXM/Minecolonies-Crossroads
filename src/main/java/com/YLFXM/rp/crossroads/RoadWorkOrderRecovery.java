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
package com.YLFXM.rp.crossroads;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.core.colony.workorders.AbstractWorkOrder;
import com.YLFXM.rp.crossroads.road.RoadManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Recovers road work-order blueprints from the persistent RoadManager index.
 * It no longer polls every second: recovery runs when a player enters the world
 * and when the Road Surveyor requests the road list.
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class RoadWorkOrderRecovery {
    @SubscribeEvent
    public static void onPlayerLogin(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.server.execute(() -> {
                recoverAll(player.server);
                // Push the road index on join: besides filling the panels, this
                // is what tells the client which blueprints it is still allowed
                // to keep, so stale copies from a previous session get pruned
                // before the player opens the native build tool.
                com.YLFXM.rp.crossroads.network.Network.sendRoads(player);
            });
        }
    }

    public static void recoverAll(final MinecraftServer server) {
        for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (!(colony.getWorld() instanceof ServerLevel level)) {
                continue;
            }
            for (final IServerWorkOrder workOrder : colony.getWorkManager().getWorkOrders().values()) {
                if (!(workOrder instanceof AbstractWorkOrder order)) {
                    continue;
                }
                final var plan = com.YLFXM.rp.crossroads.road.RoadWorkOrders.find(level, order);
                if (plan == null || plan.isRepairBusy() || plan.state()==com.YLFXM.rp.crossroads.road.RoadState.REMOVING) continue;
                if (order.getBlueprint() == null) {
                    var blueprint = com.YLFXM.rp.crossroads.road.RoadBlueprintStore.load(level, plan);
                    if (blueprint == null) {
                        Crossroads.LOGGER.warn("Cannot recover road {}: frozen blueprint missing; old construction was not regenerated",plan.id());
                        continue;
                    }
                    order.setBlueprint(blueprint, level);
                }
                plan.setState(com.YLFXM.rp.crossroads.road.RoadState.BUILDING);
                RoadManager.get(level).add(plan);
                com.YLFXM.rp.crossroads.road.RoadBuildWatcher.watch(level, plan);
                Crossroads.LOGGER.info("Tracking recovered road work order {}", plan.id());
            }
        }
    }

    private RoadWorkOrderRecovery() {
    }
}
